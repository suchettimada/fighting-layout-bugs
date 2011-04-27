/*
 * Copyright 2009 Michael Tamm
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.googlecode.fightinglayoutbugs;

import com.googlecode.fightinglayoutbugs.Screenshot.Condition;
import org.apache.commons.io.IOUtils;
import org.openqa.selenium.By;
import org.openqa.selenium.WebElement;

import javax.annotation.Nonnull;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.*;

/**
 * Represents a web page. This class was created to improve
 * performance when several {@link LayoutBugDetector}s
 * analyze the same page. It caches as much information
 * as possible.
 *
 * @author Michael Tamm
 */
public abstract class WebPage {

    private TextDetector _textDetector;
    private EdgeDetector _edgeDetector;

    private String _url;
    private String _html;
    private final List<Screenshot> _screenshots = new ArrayList<Screenshot>();
    private boolean[][] _textPixels;
    private boolean[][] _horizontalEdges;
    private boolean[][] _verticalEdges;

    private boolean _jqueryInjected;
    private boolean _textColorsBackedUp;
    private String _currentTextColor;

    /**
     * Sets the detector for {@link #getTextPixels()},
     * default is the {@link AnimationAwareTextDetector}.
     */
    public void setTextDetector(TextDetector textDetector) {
        if (_textPixels != null) {
            throw new IllegalStateException("getTextPixels() was already called.");
        }
        _textDetector = textDetector;
    }

    /**
     * Sets the detector for {@link #getHorizontalEdges()} and {@link #getVerticalEdges()},
     * default is the {@link SimpleEdgeDetector}.
     */
    public void setEdgeDetector(EdgeDetector edgeDetector) {
        if (_horizontalEdges != null) {
            throw new IllegalStateException("getHorizontalEdges() was already called.");
        }
        if (_verticalEdges != null) {
            throw new IllegalStateException("getVerticalEdges() was already called.");
        }
        _edgeDetector = edgeDetector;
    }

    public void resizeBrowserWindowTo(Dimension newBrowserWindowSize) {
        Number currentBrowserWindowWidth = (Number) executeJavaScript("return window.outerWidth");
        if (currentBrowserWindowWidth == null || currentBrowserWindowWidth.intValue() != newBrowserWindowSize.width || ((Number) executeJavaScript("return window.outerHeight")).intValue() != newBrowserWindowSize.height) {
            executeJavaScript("window.resizeTo(" + newBrowserWindowSize.width + ", " + newBrowserWindowSize.height + ")");
            if (currentBrowserWindowWidth != null) {
                // Check if resizing succeeded ...
                int browserWindowWidth = ((Number) executeJavaScript("return window.outerWidth")).intValue();
                int browserWindowHeight = ((Number) executeJavaScript("return window.outerHeight")).intValue();
                if (browserWindowWidth != newBrowserWindowSize.width || browserWindowHeight != newBrowserWindowSize.height) {
                    throw new RuntimeException("Failed to resize browser window. If you are using Chrome, see http://code.google.com/p/chromium/issues/detail?id=2091");
                }
            }
            // Clear all cached screenshots and derived values ...
            _screenshots.clear();
            _textPixels = null;
            _horizontalEdges = null;
            _verticalEdges = null;
        }
    }

    /**
     * Returns the URL of this web page.
     */
    public String getUrl() {
        if (_url == null) {
            _url = retrieveUrl();
        }
        return _url;
    }

    /**
     * Returns the source HTML of this web page.
     */
    public String getHtml() {
        if (_html == null) {
            _html = retrieveHtml();
        }
        return _html;
    }

    private class Unmodified implements Condition {
        public boolean isSatisfiedBy(Screenshot screenshot) {
            return screenshot.textColor == null;
        }
        public boolean satisfyWillModifyWebPage() {
            return false;
        }
        public void satisfyFor(WebPage webPage) {
            restoreTextColors();
        }
    }

    /**
     * Returns a screenshot of this web page.
     *
     * @param conditions conditions the taken screenshot must satisfy.
     */
    public Screenshot getScreenshot(Condition... conditions) {
        // 1.) Check if there is a condition, which modifies the web page ...
        boolean thereIsAConditionWhichModifiesTheWebPage = false;
        for (int i = 0; i < conditions.length && !thereIsAConditionWhichModifiesTheWebPage; ++i) {
            if (conditions[i].satisfyWillModifyWebPage()) {
                thereIsAConditionWhichModifiesTheWebPage = true;
            }
        }
        // 2.) If there is no condition, which modifies the web page, we need to add the Unmodified condition ...
        if (!thereIsAConditionWhichModifiesTheWebPage) {
            Condition[] temp = new Condition[1 + conditions.length];
            temp[0] = new Unmodified();
            System.arraycopy(conditions, 0, temp, 1, conditions.length);
            conditions = temp;
        }
        // 3.) Check if we have already taken a screenshot which satisfies all conditions ...
        for (Screenshot screenshot : _screenshots) {
            boolean satisfiesAllConditions = true;
            for (int i = 0; i < conditions.length && satisfiesAllConditions; ++i) {
                if (!conditions[i].isSatisfiedBy(screenshot)) {
                    satisfiesAllConditions = false;
                }
            }
            if (satisfiesAllConditions) {
                return screenshot;
            }
        }
        // 4.) No screenshot satisfied all conditions, we need to tak a new one ...
        for (Condition condition : conditions) {
            condition.satisfyFor(this);
        }
        return takeScreenshot();
    }

    /**
     * Returns a two dimensional array <tt>a</tt>, whereby <tt>a[x][y]</tt> is <tt>true</tt>
     * if the pixel with the coordinates x,y in a {@link #getScreenshot screenshot} of this web page
     * belongs to displayed text, otherwise <tt>a[x][y]</tt> is <tt>false</tt>.
     */
    public boolean[][] getTextPixels() {
        if (_textPixels == null) {
            if (_textDetector == null) {
                _textDetector = new AnimationAwareTextDetector();
            }
            _textPixels = _textDetector.detectTextPixelsIn(this);
        }
        return _textPixels;
    }

    public Collection<RectangularRegion> getRectangularRegionsCoveredBy(Collection<String> jQuerySelectors) {
        if (jQuerySelectors.isEmpty()) {
            return Collections.emptySet();
        }
        injectJQueryIfNotPresent();
        // 1.) Assemble JavaScript to select elements ...
        Iterator<String> i = jQuerySelectors.iterator();
        String js = "jQuery('" + i.next().replace("'", "\\'");
        while (i.hasNext()) {
            js += "').add('" + i.next().replace("'", "\\'");
        }
        js += "')";
        // 2.) Assemble JavaScript function to fill an array with rectangular region of each selected element ...
        js = "function() { " +
                 "var a = new Array(); " +
                  js + ".each(function(i, e) { " +
                           "var j = jQuery(e); " +
                           "var o = j.offset(); " +
                           "a.push({ top: o.top, left: o.left, width: j.width(), height: j.height() }); " +
                       "}); " +
                 "return a; " +
             "}";
        // 3.) Execute JavaScript function ...
        @SuppressWarnings("unchecked")
        List<Map<String, Number>> list = (List<Map<String, Number>>) executeJavaScript("return (" + js + ")()");
        // 4.) Convert JavaScript return value to Java return value ...
        if (list.isEmpty()) {
            return Collections.emptySet();
        }
        Collection<RectangularRegion> result = new ArrayList<RectangularRegion>(list.size());
        for (Map<String, Number> map : list) {
            float top = map.get("top").floatValue();
            float height = map.get("height").floatValue();
            float left = map.get("left").floatValue();
            float width = map.get("width").floatValue();
            result.add(new RectangularRegion((int) left, (int) top, (int) Math.round(left + width - 0.5000001), (int) Math.round(top + height - 0.5000001)));
        }
        return result;
    }

    /**
     * Returns a two dimensional array <tt>a</tt>, whereby <tt>a[x][y]</tt> is <tt>true</tt>
     * if the pixel with the coordinates x,y in a {@link #getScreenshot screenshot} of this web page
     * belongs to a horizontal edge, otherwise <tt>a[x][y]</tt> is <tt>false</tt>.
     */
    public boolean[][] getHorizontalEdges() {
        if (_horizontalEdges == null) {
            if (_edgeDetector == null) {
                _edgeDetector = new SimpleEdgeDetector();
            }
            _horizontalEdges = _edgeDetector.detectHorizontalEdgesIn(this);
        }
        return _horizontalEdges;
    }

    /**
     * Returns a two dimensional array <tt>a</tt>, whereby <tt>a[x][y]</tt> is <tt>true</tt>
     * if the pixel with the coordinates x,y in a {@link #getScreenshot screenshot} of this web page
     * belongs to a vertical edge, otherwise <tt>a[x][y]</tt> is <tt>false</tt>.
     */
    public boolean[][] getVerticalEdges() {
        if (_verticalEdges == null) {
            if (_edgeDetector == null) {
                _edgeDetector = new SimpleEdgeDetector();
            }
            _verticalEdges = _edgeDetector.detectVerticalEdgesIn(this);
        }
        return _verticalEdges;
    }

    /**
     * Returns all elements on this web page for the given find criteria.
     */
    public abstract List<WebElement> findElements(By by);

    /**
     * Executes the given JavaScript in the context of this web page.
     */
    protected abstract Object executeJavaScript(String javaScript, Object... arguments);

    /**
     * Returns the URL of this web page.
     */
    protected abstract String retrieveUrl();

    /**
     * Returns the HTML source code of this web page.
     */
    protected abstract String retrieveHtml();

    /**
     * Returns the bytes of a PNG image.
     */
    protected abstract byte[] takeScreenshotAsPng();

    void colorAllText(@Nonnull String color) {
        if (!color.equals(_currentTextColor)) {
            if (!_textColorsBackedUp) {
                injectJQueryIfNotPresent();
                executeJavaScript("jQuery('*').each(function() { var j = jQuery(this); j.attr('flb_color_backup', j.css('color')); }).size();"); // ... the trailing ".size()" will reduce the size of the response
                _textColorsBackedUp = true;
            }
            executeJavaScript("jQuery('*').css('color', '" + color + "').size();"); // ... the trailing ".size()" will reduce the size of the response
            _currentTextColor = color;
        }
    }

    void restoreTextColors() {
        if (_currentTextColor != null) {
            if (!_textColorsBackedUp) {
                throw new IllegalStateException("text colors have not been backed up.");
            }
            executeJavaScript("jQuery('*').each(function() { var j = jQuery(this); j.css('color', j.attr('flb_color_backup')); }).size();"); // ... the trailing ".size()" will reduce the size of the response
            _currentTextColor = null;
        }
    }

    private void injectJQueryIfNotPresent() {
        if (!_jqueryInjected) {
            // Check if jQuery is present ...
            if ("undefined".equals(executeJavaScript("return typeof jQuery"))) {
                String jquery = readResource("jquery-1.5.2.min.js");
                executeJavaScript(jquery);
                // Check if jQuery was successfully injected ...
                if (!"1.5.2".equals(executeJavaScript("return jQuery.fn.jquery"))) {
                    throw new RuntimeException("Failed to inject jQuery.");
                }
            }
            _jqueryInjected = true;
        }
    }

    protected String readResource(String resourceFileName) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            InputStream in = getClass().getResourceAsStream(resourceFileName);
            try {
                try {
                    IOUtils.copy(in, buf);
                } catch (IOException e) {
                    throw new RuntimeException("Could not read " + resourceFileName, e);
                }
            } finally {
                IOUtils.closeQuietly(in);
            }
        } finally {
            IOUtils.closeQuietly(buf);
        }
        try {
            return new String(buf.toByteArray(), "UTF-8");
        } catch (UnsupportedEncodingException e) {
            // Should never happen
            throw new RuntimeException(e);
        }
    }

    private Screenshot takeScreenshot() {
        byte[] bytes = takeScreenshotAsPng();
        int[][] pixels = ImageHelper.pngToPixels(bytes);
        Screenshot screenshot = new Screenshot(pixels, _currentTextColor);
        _screenshots.add(screenshot);
        return screenshot;
    }
}