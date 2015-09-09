# imagej-plugins

## Color_Reducer

Reduces the amount of colors of the currently active image in ImageJ.
The plugin searches for color intervals in the histogram of hues. An interval is considered dominant, if the sum of the hue frequencies within the interval is noticeably bigger than the sums of other intervals. To reduce colors, a non-dominant color interval is replaced by the most similar dominant interval.

## Histogram_Equalization
Executes a simple histogram equalization. -> todo: upload
