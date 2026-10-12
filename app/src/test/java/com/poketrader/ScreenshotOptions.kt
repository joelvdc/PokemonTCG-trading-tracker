package com.poketrader

import com.dropbox.differ.SimpleImageComparator
import com.github.takahirom.roborazzi.RoborazziOptions

/**
 * How the screenshot tests compare a screen with its reference picture. Windows and Linux smooth the
 * edges of letters a hair differently (a few steps out of 255 on some pixels, nothing the eye sees),
 * so colour differences that small are ignored. Anything visible (a changed word, number, colour or
 * layout) still counts as a change. Together with the fixed time zone and language in
 * app/build.gradle.kts this lets the same reference pictures pass on GitHub's machines and on a PC.
 */
val screenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(imageComparator = SimpleImageComparator(maxDistance = 0.03f)),
)
