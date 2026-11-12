package com.example.thermohammer.data

/**
 * A phone's name for people: the manufacturer in front of the model, unless the model already starts with it. That is how
 * Android runs are stored ("HONOR VKP-NX9", "HONOR 600 Pro"), so a plain join would print "HONOR HONOR 600 Pro".
 */
fun deviceDisplayName(manufacturer: String, model: String): String =
    if (manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true)) model else "$manufacturer $model".trim()
