package com.souleven.wap

import android.app.Activity
import android.content.Intent
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Runs inside WhatsApp. Adds an "AI Auto-Reply" entry that opens this module's settings page:
 *  1) as a row in WhatsApp's Settings screen (best effort, found by view-tree heuristics)
 *  2) as an item in the home screen's three-dot menu (fallback)
 * WhatsApp's own login / chats / chat screens are not touched.
 */
object UiHooks {

    private const val MENU_ID = 0x7A117001
    private const val ROW_TAG = "wap_ai_row"
    private const val MODULE_PKG = "com.souleven.wap"
    private const val MODULE_ACT = "com.souleven.wap.ai.SettingsActivity"
    private const val LABEL = "AI Auto-Reply"
    // Class names differ per WhatsApp version; 25.8.63 uses the first entry of each list (verified in its APK).
    private val HOME_CLASSES = listOf("com.whatsapp.home.ui.HomeActivity", "com.whatsapp.HomeActivity")
    private val SETTINGS_CLASSES = listOf("com.whatsapp.settings.ui.SettingsTabActivity", "com.whatsapp.settings.Settings")

    fun install(lpparam: LoadPackageParam) {
        val loader = lpparam.classLoader
        try { hookHome(loader) } catch (t: Throwable) { XposedBridge.log("WAP: home hook failed: ${t.message}") }
        try { hookSettings(loader) } catch (t: Throwable) { XposedBridge.log("WAP: settings hook failed: ${t.message}") }
    }

    private fun open(a: Activity) {
        try {
            a.startActivity(
                Intent().setClassName(MODULE_PKG, MODULE_ACT).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (t: Throwable) {
            Toast.makeText(a, "WAPlus module app installed nahi hai", Toast.LENGTH_LONG).show()
        }
    }

    private fun hookHome(loader: ClassLoader) {
        val home = HOME_CLASSES.firstNotNullOfOrNull { XposedHelpers.findClassIfExists(it, loader) }
        if (home == null) {
            XposedBridge.log("WAP: home activity not found")
            return
        }
        val homeName = home.name
        XposedBridge.log("WAP: home hook on $homeName")
        XposedHelpers.findAndHookMethod(home, "onCreateOptionsMenu", Menu::class.java, object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != homeName) return
                val menu = param.args[0] as? Menu ?: return
                if (menu.findItem(MENU_ID) == null) menu.add(0, MENU_ID, 9999, LABEL)
            }
        })
        XposedHelpers.findAndHookMethod(home, "onOptionsItemSelected", MenuItem::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != homeName) return
                val item = param.args[0] as? MenuItem ?: return
                if (item.itemId == MENU_ID) {
                    open(param.thisObject as Activity)
                    param.result = true
                }
            }
        })
    }

    private fun hookSettings(loader: ClassLoader) {
        val cls = SETTINGS_CLASSES.firstNotNullOfOrNull { XposedHelpers.findClassIfExists(it, loader) }
        if (cls == null) {
            XposedBridge.log("WAP: settings activity not found")
            return
        }
        val settingsName = cls.name
        XposedBridge.log("WAP: settings hook on $settingsName")
        XposedHelpers.findAndHookMethod(cls, "onResume", object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (param.thisObject.javaClass.name != settingsName) return
                val a = param.thisObject as Activity
                a.window.decorView.post {
                    try { inject(a) } catch (t: Throwable) { XposedBridge.log("WAP: inject failed: ${t.message}") }
                }
            }
        })
    }

    private fun inject(a: Activity) {
        val root = a.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (root.findViewWithTag<View>(ROW_TAG) != null) return
        val container = findContainer(root) ?: run {
            XposedBridge.log("WAP: settings list container not found")
            return
        }
        val sample = firstText(container)
        val d = a.resources.displayMetrics.density
        val row = TextView(a)
        row.tag = ROW_TAG
        row.text = LABEL
        if (sample != null) {
            row.setTextSize(TypedValue.COMPLEX_UNIT_PX, sample.textSize)
            row.setTextColor(sample.textColors)
            row.typeface = sample.typeface
        } else {
            row.textSize = 16f
        }
        row.setPadding((20 * d).toInt(), (16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt())
        row.isClickable = true
        val tv = TypedValue()
        if (a.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId)
        }
        row.setOnClickListener { open(a) }
        container.addView(row, minOf(3, container.childCount))
    }

    /** First vertical LinearLayout (with several rows) that sits directly inside a ScrollView. */
    private fun findContainer(v: View): LinearLayout? {
        if (v is ViewGroup && v.javaClass.name.contains("ScrollView") && v.childCount == 1) {
            val c = v.getChildAt(0)
            if (c is LinearLayout && c.orientation == LinearLayout.VERTICAL && c.childCount >= 3) return c
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = findContainer(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }

    private fun firstText(v: View): TextView? {
        if (v is TextView && !v.text.isNullOrBlank()) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val r = firstText(v.getChildAt(i))
                if (r != null) return r
            }
        }
        return null
    }
}
