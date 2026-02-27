package com.pgeneratorplus.android.network

import android.util.Log

/**
 * Simple XML parser for Resolve calibration protocol.
 * Parses the XML data sent by DisplayCAL, Calman, and ColourSpace.
 */
object XmlParser {
 private const val TAG = "XmlParser"

 data class CalibrationData(
  val colorRed: Float,
  val colorGreen: Float,
  val colorBlue: Float,
  val backgroundRed: Float,
  val backgroundGreen: Float,
  val backgroundBlue: Float,
  val geometryX: Float,
  val geometryY: Float,
  val geometryCX: Float,
  val geometryCY: Float,
  val targetBits: Int
 ) {
  val isFullField: Boolean
   get() = geometryX == 0f && geometryY == 0f && geometryCX == 1f && geometryCY == 1f
 }

 private fun extractAttribute(xml: String, attributeName: String): Float? {
  val pattern = "$attributeName=\""
  val startIdx = xml.indexOf(pattern)
  if (startIdx == -1) return null
  val valueStart = startIdx + pattern.length
  val valueEnd = xml.indexOf("\"", valueStart)
  if (valueEnd == -1) return null
  return xml.substring(valueStart, valueEnd).toFloatOrNull()
 }

 private fun extractIntAttribute(xml: String, attributeName: String): Int? {
  return extractAttribute(xml, attributeName)?.toInt()
 }

 fun parseCalibrationXml(xml: String): CalibrationData? {
  try {
   val colorSection = extractElement(xml, "color") ?: run {
    Log.e(TAG, "Could not find <color> element")
    return null
   }

   val colorRed = extractAttribute(colorSection, "red") ?: 0f
   val colorGreen = extractAttribute(colorSection, "green") ?: 0f
   val colorBlue = extractAttribute(colorSection, "blue") ?: 0f
   val bits = extractIntAttribute(colorSection, "bits") ?: 8

   val bgSection = extractElement(xml, "background")
   val bgRed = if (bgSection != null) extractAttribute(bgSection, "red") ?: 0f else 0f
   val bgGreen = if (bgSection != null) extractAttribute(bgSection, "green") ?: 0f else 0f
   val bgBlue = if (bgSection != null) extractAttribute(bgSection, "blue") ?: 0f else 0f

   val geoSection = extractElement(xml, "geometry")
   val geoX = if (geoSection != null) extractAttribute(geoSection, "x") ?: 0f else 0f
   val geoY = if (geoSection != null) extractAttribute(geoSection, "y") ?: 0f else 0f
   val geoCX = if (geoSection != null) extractAttribute(geoSection, "cx") ?: 1f else 1f
   val geoCY = if (geoSection != null) extractAttribute(geoSection, "cy") ?: 1f else 1f

   return CalibrationData(
    colorRed, colorGreen, colorBlue,
    bgRed, bgGreen, bgBlue,
    geoX, geoY, geoCX, geoCY,
    bits
   )
  } catch (e: Exception) {
   Log.e(TAG, "Error parsing calibration XML", e)
   return null
  }
 }

 fun parseLightspaceCalibrationXml(xml: String): CalibrationData? {
  try {
   val rectSection = extractElement(xml, "rectangle") ?: return parseCalibrationXml(xml)
   val bits = extractIntAttribute(rectSection, "bits") ?: 8

   val colorSection = extractElement(rectSection, "color") ?: run {
    Log.e(TAG, "Could not find <color> in <rectangle>")
    return null
   }

   val colorRed = extractAttribute(colorSection, "red") ?: 0f
   val colorGreen = extractAttribute(colorSection, "green") ?: 0f
   val colorBlue = extractAttribute(colorSection, "blue") ?: 0f

   val bgSection = extractElement(rectSection, "background")
   val bgRed = if (bgSection != null) extractAttribute(bgSection, "red") ?: 0f else 0f
   val bgGreen = if (bgSection != null) extractAttribute(bgSection, "green") ?: 0f else 0f
   val bgBlue = if (bgSection != null) extractAttribute(bgSection, "blue") ?: 0f else 0f

   val geoSection = extractElement(rectSection, "geometry")
   val geoX = if (geoSection != null) extractAttribute(geoSection, "x") ?: 0f else 0f
   val geoY = if (geoSection != null) extractAttribute(geoSection, "y") ?: 0f else 0f
   val geoCX = if (geoSection != null) extractAttribute(geoSection, "cx") ?: 1f else 1f
   val geoCY = if (geoSection != null) extractAttribute(geoSection, "cy") ?: 1f else 1f

   return CalibrationData(
    colorRed, colorGreen, colorBlue,
    bgRed, bgGreen, bgBlue,
    geoX, geoY, geoCX, geoCY,
    bits
   )
  } catch (e: Exception) {
   Log.e(TAG, "Error parsing LightSpace XML", e)
   return null
  }
 }

 fun parse(xml: String): CalibrationData? {
  return if (xml.contains("<rectangle")) {
   parseLightspaceCalibrationXml(xml)
  } else {
   parseCalibrationXml(xml)
  }
 }

 private fun extractElement(xml: String, elementName: String): String? {
  val selfClosingRegex = Regex("<$elementName\\s[^>]*/>", RegexOption.DOT_MATCHES_ALL)
  val selfClosingMatch = selfClosingRegex.find(xml)
  if (selfClosingMatch != null) return selfClosingMatch.value

  val openTag = "<$elementName"
  val closeTag = "</$elementName>"
  val openIdx = xml.indexOf(openTag)
  if (openIdx == -1) return null

  val closeIdx = xml.indexOf(closeTag, openIdx)
  if (closeIdx == -1) {
   val tagEnd = xml.indexOf(">", openIdx)
   if (tagEnd != -1) return xml.substring(openIdx, tagEnd + 1)
   return null
  }

  return xml.substring(openIdx, closeIdx + closeTag.length)
 }
}
