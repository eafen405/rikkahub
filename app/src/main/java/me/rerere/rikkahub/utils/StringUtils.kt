package me.rerere.rikkahub.utils

fun String.escapeHtml(): String = android.text.TextUtils.htmlEncode(this)
