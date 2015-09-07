# imagej-plugins

## Color_Reducer

Reduces color hues of a picture.
The plugin searches for color intervals in the histogram of color hues. An interval is considered dominant, if the sum of the hue frequencies within the interval is noticeably bigger than the sum of other intervals. To reduce colors, a non-dominant color interval is replaced by the most similar dominant interval.