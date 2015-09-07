import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.plugin.filter.PlugInFilter;
import ij.process.ColorProcessor;
import ij.process.ImageProcessor;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import javax.swing.ButtonModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;

public class Color_Reducer implements PlugInFilter {

    public static final int NUMBER_OF_COLORS = 360;
    public static final int RGB_SAMPLE_MAX = 255;


    private int[] histogram;
    private static final boolean SHOW_HISTOGRAM = true;

    private static final boolean DEBUG_MODE = false;

    private ImageProcessor imageProcessor = null;
    private ImageProcessor downsizedImageProcessor = null;

    private ImagePlus result;

    private List<ColorInterval> colorIntervals;

    private int pixelCount;

    private boolean resizeToPreviewSize = false;
    public static final int INPUT_WIDTH_MAX = 1920;
    public static final int INPUT_HEIGHT_MAX = 1080;
    public static final int PREVIEW_WIDTH = 640;

    private static final int DIALOG_SLIDER_DEFAULT_VALUE = 2;
    private JProgressBar progressBar;
    private JSlider slider;
    private JFrame sliderFrame;
    private ImagePlus histogramWindow;
    private JButton btnOkay;
    private JButton btnCancel;

    /**
     * Converts an array of rgb samples in the range of [0, 255] to hsv with h in [0, 360], s and v in [0,1]
     * @param rgb integer array of rgb samples
     * @return double array of hsv components
     */
    public double[] rgb2hsv(int[] rgb) {
        double r = rgb[0] / 255f, g = rgb[1] / 255f, b = rgb[2] / 255f;
        double max, min, delta;

        max = r > g ? (r > b ? r : b) : (g > b ? g : b);
        min = r < g ? (r < b ? r : b) : (g < b ? g : b);

        delta = max - min;

        double h = 0, s = delta / max, v = max;

        if (max == min) {
            h = 0;
        } else if (max == r) {
            h = (g - b) / delta;
        } else if (max == g) {
            h = 2 + (b - r) / delta;
        } else if (max == b) {
            h = 4 + (r - g) / delta;
        }

        h *= 60;
        h += h < 0 ? 360 : 0;

        return new double[] {h, s, v};
    }

    /**
     * Converts an array of hsv components to an array of rgb samples. H component should be in the range of [0, 360], S and V in [0, 1].
     * @param  hsv array of hsv components
     * @return rgb array
     */
    public int[] hsv2rgb(double[] hsv) {
        double h = hsv[0], s = hsv[1], v = hsv[2];
        int hi = (int) Math.floor(h / 60);
        double f = h / 60 - hi;
        double p = v * (1 - s);
        double q = v * (1 - s * f);
        double t = v * (1 - s * (1 - f));

        double r, g, b;

        switch (hi) {
            case 0:
            case 6:
                r = v; g = t; b = p;
                break;
            case 1:
                r = q; g = v; b = p;
                break;
            case 2:
                r = p; g = v; b = t;
                break;
            case 3:
                r = p; g = q; b = v;
                break;
            case 4:
                r = t; g = p; b = v;
                break;
            default: //case 5:
                r = v; g = p; b = q;
                break;
        }

        return new int[] {(int) (r * RGB_SAMPLE_MAX), (int) (g * RGB_SAMPLE_MAX), (int) (b * RGB_SAMPLE_MAX)};
    }

    /**
     * Lookup to find appropiate histogram container
     * @param h
     * @param histogramSize
     * @return
     */
    public int histogramIndex(double h, int histogramSize) {
        // histogramSize <=> NUMBER_OF_COLORS
        return (int) Math.floor((h / 360.0) * histogramSize);
    }

    public final static int STATE_IS_RISING_EDGE = 0;
    public final static int STATE_IS_FALLING_EDGE = 1;
    public final static int STATE_IS_PLATEAU = 2;
    public final static int STATE_IS_GUESSING = 4;

    /**
     * Analyzes a given histogram and searches for color intervals by using a hill climbing algorithm.
     */
    public ArrayList<ColorInterval> findColorIntervals(int[] h) {
        int state = STATE_IS_GUESSING;

        int leftBoundary = 0;
        int rightBoundary = 0;

        int maxKey = h.length - 1;
        ArrayList<ColorInterval> returnList = new ArrayList<ColorInterval>();

        for (int i = 1; i < h.length; i++) {
            int val = h[i];
            int pre = h[i-1];

            switch (state) {
                case STATE_IS_GUESSING:
                    if (val > pre) {
                        state = STATE_IS_RISING_EDGE;
                        leftBoundary = i;
                    }

                    if (val < pre) {
                        state = STATE_IS_FALLING_EDGE;
                    }

                    if (val == pre) {
                        state = STATE_IS_PLATEAU;
                    }
                    break;
                case STATE_IS_FALLING_EDGE:
                    if (i == maxKey) {
                        rightBoundary = i;
                        returnList.add(new ColorInterval(leftBoundary, rightBoundary, histogram));
                        break;
                    }

                    if (val > pre) {
                        state = STATE_IS_RISING_EDGE;
                        rightBoundary = i;
                        returnList.add(new ColorInterval(leftBoundary, rightBoundary, histogram));

                        leftBoundary = i;
                    } else if (val == 0) {
                        state = STATE_IS_PLATEAU;
                        rightBoundary = i;
                        returnList.add(new ColorInterval(leftBoundary, rightBoundary, histogram));
                    }

                    break;
                case STATE_IS_RISING_EDGE:
                    if (val < pre) {
                        state = STATE_IS_FALLING_EDGE;
                    }

                    if (i == maxKey) {
                        rightBoundary = i;
                        returnList.add(new ColorInterval(leftBoundary, rightBoundary, histogram));
                    }
                    break;
                case STATE_IS_PLATEAU:
                    if (val > pre) {
                        state = STATE_IS_RISING_EDGE;
                        leftBoundary = i;
                    }

                    if (val < pre) {
                        state = STATE_IS_FALLING_EDGE;
                    }

                    if (i == maxKey && val > 0) {
                        rightBoundary = i;
                        returnList.add(new ColorInterval(leftBoundary, rightBoundary, histogram));
                    }
                    break;
            }
        }

        if (rightBoundary == maxKey) {
            int last = returnList.size() - 1;
            ColorInterval lastInterval = returnList.remove(last);

            returnList.get(0).setBegin(lastInterval.getBegin());
        }
        return returnList;
    }

    /**
     * Analyzes a given histogram and searches for color intervals by using a hill climbing algorithm.
     * @deprecated
     */
    public List<ColorInterval> getColorIntervals(int[] h) {
        // TODO: merge first and last interval
        List<ColorInterval> res = new ArrayList<ColorInterval>(h.length);
        boolean isFallingEdge = false;
        int leftBound = 0;
        ColorInterval ci;

        for (int i = 1; i < h.length; i++) {
            if (h[i - 1] > h[i]) {
                isFallingEdge = true;
            }

            if (h[i - 1] < h[i] && isFallingEdge) {
                isFallingEdge = false;
                ci = new ColorInterval(leftBound, i - 1, h);
                res.add(ci);
                leftBound = i;
                log(ci.toString());
            }
        }
        res.add(new ColorInterval(leftBound, histogram.length - 1, histogram));

        // res.get(0).merge(ci);
        return res;
    }

    private void renderHistogram(int[] histogram) {
        renderHistogram(histogram, 50, 50);
    }

    private void renderHistogram(int[] histogram, int verticalGuides, int horizontalGuides) {
        int height = 200;
        int width = 200;
        int verticalGuidesMargin = verticalGuides;
        int horizontalGuidesMargin = horizontalGuides;

        ImageProcessor ip = new ColorProcessor(width, height);
        ip.setColor(Color.WHITE);
        ip.fill();

        // vertical grid lines
        if (verticalGuidesMargin > 0) {
            ip.setColor(Color.LIGHT_GRAY);
            for (int guidePosition = verticalGuidesMargin; guidePosition < width; guidePosition += verticalGuidesMargin) {
                ip.drawLine(guidePosition, 0, guidePosition, height);
            }
        }

        // horizontal grid lines
        if (horizontalGuidesMargin > 0) {
            ip.setColor(Color.LIGHT_GRAY);
            for (int guidePosition = horizontalGuidesMargin; guidePosition < width; guidePosition += horizontalGuidesMargin) {
                ip.drawLine(0, guidePosition, width, guidePosition);
            }
        }

        ip.setColor(Color.BLUE);

        int yMax = getHistogramMax(histogram);
        int yLast = (yMax - histogram[0]) * height / yMax;
        int x, y;
        float size = (float) histogram.length;

        for (int i = 1; i < histogram.length; i++) {
            x = (int) (i / size * width);
            y = (yMax - histogram[i]) * height / yMax;
            ip.drawLine(x - 1, yLast, x, y);
            yLast = y;
        }

        histogramWindow = new ImagePlus("HSVHistogramm", ip);
        histogramWindow.show();
    }

    /**
     * Get greates value of a given histogram.
     * @param histogram
     * @return greatest value
     */
    private int getHistogramMax(int[] histogram) {
        int max = 0;
        for (int i = 0; i < histogram.length; i++) {
            if (max < histogram[i]) {
                max = histogram[i];
            }
        }
        return max;
    }

    @Override
    public int setup(String arg, ImagePlus imp) {

        imageProcessor = imp.getProcessor();

        histogram = new int[NUMBER_OF_COLORS];
        pixelCount = imageProcessor.getPixelCount();
        result = imp.createImagePlus();

        int imgSize = imp.getProcessor().getHeight() * imp.getProcessor().getWidth();

        if (imgSize >= INPUT_WIDTH_MAX * INPUT_HEIGHT_MAX) {
            resizeToPreviewSize = true;
            downsizedImageProcessor = imageProcessor.resize(PREVIEW_WIDTH);
        }

        return DOES_RGB;
    }

    @Override
    public void run(ImageProcessor ip) {
        int width = ip.getWidth(), height = ip.getHeight();

        int[] rgb = new int[3];
        double[] hsv = new double[3];

        int index;

        // create histogram for hue (hsv)
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                ip.getPixel(x, y, rgb);
                hsv = rgb2hsv(rgb);
                index = histogramIndex(hsv[0], histogram.length);
                histogram[index]++;
            }
        }

        log(Arrays.toString(histogram));

        if (SHOW_HISTOGRAM) {
            renderHistogram(histogram);
        }

        colorIntervals = getColorIntervals(histogram);
        // todo: we want to replace getColorIntervals() by findColorIntervals()
        // ArrayList<ColorInterval> test = findColorIntervals(histogram);

        Collections.sort(colorIntervals);
        final int colorCount = colorIntervals.size();

        result.setTitle("Ausgabe");
        result.show();

        javax.swing.SwingUtilities.invokeLater(new Runnable() {
            public void run() {
                createAndShowDialog(colorCount);
                fireSliderChangeEvent();
            }
        });
    }

    /**
     * Triggers eventhandling if slider is dragged.
     * @see Color_Reducer#createAndShowDialog()
     */
    protected void fireSliderChangeEvent() {
        for (ChangeListener cl : slider.getChangeListeners()) {
            cl.stateChanged(new ChangeEvent(slider));
        }
    }

     /**
     * Converts input image into grayscale image. Conversion uses RGB -> HSV conversion and sets hue value to zero,
     * then converts back to RGB.
     * @param ImageProcessor ipin ImageProcessor input image data
     * @return ImageProcessor Grayscale ImageProcessor
     * @see CalculationTask#done()
     */
    public ImageProcessor desaturateColors(ImageProcessor ipin) {
        int width = ipin.getWidth(), height = ipin.getHeight();
        ImageProcessor ipout = new ColorProcessor(width, height);

        int[] rgb;
        double[] hsv;

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                rgb = ipin.getPixel(x, y, null);
                hsv = rgb2hsv(rgb);

                hsv[1] = 0;

                rgb = hsv2rgb(hsv);
                ipout.putPixel(x, y, rgb);
            }
        }

        return ipout;
    }

    /**
     * Replaces a color variant by its most similar variant which is already more dominant in the image.
     * @param ipin ImageProcessor input image
     * @param colorLevel int Number of dominant color variants after reduction
     * @see ReductionTask#doInBackground()
     * @see desaturateColors()
     * @return
     */
    synchronized private ImageProcessor reduceColors(ImageProcessor ipin, int colorLevel) {
        int width = ipin.getWidth(), height = ipin.getHeight();

        int[] currentPixel = new int[3];
        double[] hsv;
        int translatedHue;

        int[] _cache = new int[NUMBER_OF_COLORS];
        Arrays.fill(_cache, Integer.MIN_VALUE);

        ImageProcessor ipout = ipin.duplicate(); //new ColorProcessor(width, height);
        //Set<ColorInterval> dominatingColors = Collections.synchronizedSet(new HashSet<ColorInterval>(colorLevel));

        ArrayList<ColorInterval> dominatingColors = new ArrayList<ColorInterval>();

        int lsize = colorIntervals.size();
        for (ColorInterval i : colorIntervals.subList(lsize - colorLevel, lsize)) {
            i.setDominant(true);
            dominatingColors.add(i);
        }

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                ipin.getPixel(x, y, currentPixel);
                hsv = rgb2hsv(currentPixel);

                int index = histogramIndex(hsv[0], histogram.length);

                if (_cache[index] == Integer.MIN_VALUE) {
                    for (ColorInterval ci : colorIntervals) {
                        if (ci.includes(index)) {
                            if (!ci.isDominant()) {
                                int minD = Integer.MAX_VALUE;
                                ColorInterval minI = null;

                                for (ColorInterval di : dominatingColors) {
                                    int n = histogram.length;
                                    int distance = Math.min(Math.abs(index - di.getBegin()), Math.abs(index - di.getEnd()));

                                    if (n - Math.abs(index - di.getBegin()) < distance)
                                        distance = n - Math.abs(index - di.getBegin());

                                    if (n - Math.abs(index - di.getEnd()) < distance)
                                        distance = n - Math.abs(index - di.getEnd());

                                    if (minD > distance) {
                                        minD = distance;
                                        minI = di;
                                    }
                                }

                                if (minI instanceof ColorInterval) {
                                    // actual color reduction happens here
                                    translatedHue = minI.getMaxIndex();
                                    //translatedHue = (index - ci.getBegin()) * minI.getWidth() / ci.getWidth() + minI.getBegin();
                                    _cache[index] = translatedHue;
                                }
                            }
                        }
                    }
                }

                hsv[0] = _cache[index] / (double) histogram.length * 360f;
                ipout.putPixel(x, y, hsv2rgb(hsv));

                // @todo: show progress
            }
            // reconstitute a list of non-dominant color intervals for renewed reductions
            for (ColorInterval i : colorIntervals.subList(lsize - colorLevel, lsize)) {
                i.setDominant(false);
            }
        }
        return ipout;
    }

    /**
     * Base class for calculation threads.
     * @see SwingWorker
     */
    abstract protected class CalculationTask extends SwingWorker<ImageProcessor, Void> {
        @Override
        abstract protected ImageProcessor doInBackground();

        @Override
        public void done() {
            sliderFrame.setCursor(null);
            progressBar.setValue(0);
            progressBar.setIndeterminate(false);

            try {
                ImageProcessor ip = get();
                result.setProcessor(ip);

                if (!result.isVisible()) {
                    result.show();
                }
            } catch (InterruptedException ex) {

            } catch (ExecutionException ex) {

            }
        }

        public void exposeSetProgress(int progress) {
            setProgress(progress);
        }

        public int exposeGetProgress() {
            return getProgress();
        }
    }

    /**
     * This task is used to completely desaturate a given image.
     *
     * @see Color_Reducer#desaturateColors()
     */
    protected class DesaturationTask extends CalculationTask {
        public ImageProcessor doInBackground() {
            ImageProcessor theProcessor = imageProcessor;
            if (resizeToPreviewSize) {
                theProcessor = downsizedImageProcessor;
            }

            ImageProcessor returnProcessor = desaturateColors(theProcessor);
            return returnProcessor;
        }
    }

    /**
     * This task is used to reduce colors of a given image.
     *
     * @see Color_Reducer#reduceColors()
     */
    protected class ReductionTask extends CalculationTask {
        /**
         * number of dominant colors
         */
        private final int level;

        /**
         * @param level number of dominant colors
         */
        public ReductionTask(int level) {
            this.level = level;
        }

        @Override
        /**
         * @see CalculationTask#doInBackground()
         * @see DesaturationTask#doInBackground()
         */
        protected ImageProcessor doInBackground() {
            ImageProcessor theProcessor = imageProcessor;
            if (resizeToPreviewSize) {
                theProcessor = downsizedImageProcessor;
            }


            ImageProcessor returnProcessor = reduceColors(theProcessor, level);
            return returnProcessor;
        }
    }

    // @todo: slider label
    public void createAndShowDialog(int sliderMax) {
        sliderFrame = new JFrame("Farben reduzieren");

        JPanel top = new JPanel();
        JPanel center = new JPanel();
        JPanel bottom = new JPanel();

        center.setLayout(new BorderLayout());

        JCheckBox previewCheckBox = new JCheckBox();
        previewCheckBox.setText("Vorschau");
        previewCheckBox.setSelected(resizeToPreviewSize);

        previewCheckBox.setEnabled(resizeToPreviewSize);

        previewCheckBox.addItemListener(new ItemListener() {

            public void itemStateChanged(ItemEvent e) {
                resizeToPreviewSize = ((JCheckBox) e.getSource()).isSelected();
                fireSliderChangeEvent();
            }
        });

        top.add(previewCheckBox);

        //sliderFrame.setSize(250, 150);
        sliderFrame.setVisible(true);
        sliderFrame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);

        WindowManager.addWindow(sliderFrame);

        final JTextField text = new JTextField(10);
        text.setText(DIALOG_SLIDER_DEFAULT_VALUE + "");
        text.setEditable(false);

        progressBar = new JProgressBar(0, 100);
        progressBar.setValue(0);
        progressBar.setIndeterminate(true);

        Dimension tiny = new Dimension(20, 20);

        slider = new JSlider(0, sliderMax, DIALOG_SLIDER_DEFAULT_VALUE);
        slider.setPreferredSize(tiny);
        progressBar.setPreferredSize(tiny);
        text.setPreferredSize(tiny);

        center.setMaximumSize(new Dimension(100, 80));

        slider.addChangeListener(new ChangeListener() {

            @Override
            public void stateChanged(ChangeEvent e) {
                final JSlider s = (JSlider) e.getSource();
                text.setText(String.valueOf(s.getValue()));

                PropertyChangeListener changeListener = new PropertyChangeListener() {

                    public void propertyChange(PropertyChangeEvent evt) {
                        if ("progress".equalsIgnoreCase(evt.getPropertyName())) {
                            int progress = (Integer) evt.getNewValue();
                            progressBar.setValue(progress);
                        }
                    }
                };

                if (!s.getValueIsAdjusting()) {
                    // let's display a fancy hourglass cursor
                    sliderFrame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
                    // get slider's value
                    int sliderValue = s.getValue();

                    if (sliderValue != 0) { // we want to reduce colors
                        CalculationTask ct = new ReductionTask(sliderValue);
                        ct.addPropertyChangeListener(changeListener);
                        ct.execute();
                    } else { // we just want to desaturate the image, no real reduction
                        CalculationTask ct = new DesaturationTask();
                        ct.addPropertyChangeListener(changeListener);
                        ct.execute();
                    }
                }
            }
        });

        JPanel p = new JPanel();
        p.setLayout(new FlowLayout());
        p.setPreferredSize(new Dimension(250, 100));
        p.setMinimumSize(new Dimension(250, 100));

        sliderFrame.addWindowListener(new WindowAdapter() {

            @Override
            public void windowClosing(WindowEvent e) {
                WindowManager.removeWindow(sliderFrame);
                sliderFrame.dispose();
                result.changes = false;
                result.close();
                histogramWindow.close();
            }
        });

        btnOkay = new JButton("OK");
        btnOkay.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sliderFrame.dispose();
                histogramWindow.close();
                WindowManager.removeWindow(sliderFrame);

                resizeToPreviewSize = false;

                fireSliderChangeEvent();
            }
        });
        btnOkay.setSize(100, 25);

        btnCancel = new JButton("Abbrechen");
        btnCancel.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                sliderFrame.dispose();
                result.changes = false;
                result.close();
                histogramWindow.close();
            }
        });

        center.add(slider, BorderLayout.NORTH);
        center.add(text, BorderLayout.CENTER);
        center.add(progressBar, BorderLayout.SOUTH);

        bottom.add(btnOkay);
        bottom.add(btnCancel);

        sliderFrame.setLayout(new BorderLayout());
        sliderFrame.add(top, BorderLayout.NORTH);
        sliderFrame.add(center, BorderLayout.CENTER);
        sliderFrame.add(bottom, BorderLayout.SOUTH);
        sliderFrame.pack();
        sliderFrame.toFront();
    }

    private void log(String msg) {
        if (DEBUG_MODE) {
            IJ.log(msg);
        }
    }
}

/**
 * A ColorInterval is a local maximum and its edges in the histogram. The dominance of a color hue
 * is calculated by interval's area.
 */
final class ColorInterval implements Comparable<ColorInterval> {

    /**
     * left border
     */
    private int begin;
    /**
     * right border
     */
    private int end;

    /**
     * cumulated frequency of histogram values within the interval
     */
    private int frequency;

    /**
     * max value in interval
     */
    private int max;

    /**
     * absolute position of max value in histogram. This is not a position
     * within the interval.
     */
    private int maxPos;
    private static int cnt = 0;

    /**
     * Is this interval one of those considered dominant?
     */
    private boolean isDominant = false;

    private static int[] histogram;

    public ColorInterval(int begin, int end, int[] histogram) {
        this.begin = begin;
        this.end = end;
        ColorInterval.histogram = histogram;
        frequency = determineFrequency();
        max = determineMax();
    }

    /**
     * Calculates the cumulated frequency of histogram values within the interval.
     * This method should only be executed for initial calculation or updating the frequency.
     * If you wish to obtain the frequency, use <b>getFrequency()</b>
     * @see ColorReducer#getFrequency()
     * @return int frequency
     */
    private int determineFrequency() {
        int res = 0;
        if (begin < end) {
            for (int i = begin; i <= end; i++) {
                res += histogram[i];
            }
        } else {
            for (int i = begin; i < histogram.length; i++) {
                res += histogram[i];
            }

            for (int i = end; i >= 0; i--) {
                res += histogram[i];
            }
        }
        return res;
    }

    /**
     * Find max value in histogram, within the borders of the interval. The position
     * of max value is also stored.
     *
     * @see getMax()
     * @see getMaxIndex()
     * @return max value
     */
    private int determineMax() {
        max = histogram[begin];
        maxPos = begin;
        if (begin < end) { // normaler Fall
            for (int i = begin + 1; i <= end; i++) {
                if (histogram[i] > max) {
                    max = histogram[i];
                    maxPos = i;
                }
            }
        } else { // interval is open? (is at start or end of histogram)
            for (int i = end; i >= 0; i--) {
                if (histogram[i] > max) {
                    max = histogram[i];
                    maxPos = i;
                }
            }
            for (int i = begin; i < histogram.length - 1; i++) {
                if (histogram[i] > max) {
                    max = histogram[i];
                    maxPos = i;
                }
            }
        }
        return max;
    }

    public void setBegin(int newBegin) {
        if (newBegin < histogram.length - 1) {
            begin = newBegin;
            frequency = determineFrequency();
            max = determineMax();
        }
    }

    /**
     * Compare by cumulated frequencies.
     */
    @Override
    public int compareTo(ColorInterval o) {
        int myFreq = getFrequency();
        int otherFreq = o.getFrequency();

        if (myFreq < otherFreq) {
            return -1;
        } else if (myFreq > otherFreq) {
            return 1;
        }
        return 0;
    }

    /**
     * @return int Width of interval.
     */
    public int getWidth() {
        return end - begin + 1;
    }

    /**
     * @return int Left border of interval.
     */
    public int getBegin() {
        return begin;
    }

    /**
     * @return int Right border of interval.
     */
    public int getEnd() {
        return end;
    }

    /**
     * @return int cumulated frequency
     */
    public int getFrequency() {
        return frequency;
    }

    /**
     * @see ColorInterval#determineMax()
     * @return max value
     */
    public int getMax() {
        return max;
    }

    /**
     * @see ColorInterval#determineMax()
     * @return int Position of max value.
     */
    public int getMaxIndex() {
        return maxPos;
    }

    @Override
    public String toString() {
        return "begin: " + begin + " end: " + end;
    }

    /**
     * @param flag true => is dominant; false => is not dominant
     */
    public void setDominant(boolean flag) {
        isDominant = flag;
    }

    public boolean isDominant() {
        return isDominant;
    }

    /**
     * Prüft ob eine gegebene Histogrammposition von diesem Intervall erfasst wird oder nicht.
     * @return true wenn der Wert im Intervall liegt, false anderenfalls
     */
    boolean includes(int index) {
        return index >= begin && index <= end;
    }
}