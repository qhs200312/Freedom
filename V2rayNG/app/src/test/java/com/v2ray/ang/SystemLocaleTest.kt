package com.v2ray.ang

import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import com.v2ray.ang.util.Utils
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Locale

class SystemLocaleTest {
    @Test
    fun followSystemIgnoresTheAppLocalePreviouslyInstalledAsTheProcessDefault() {
        val resources = mock<Resources>()
        val configuration = mock<Configuration>()
        val systemLocales = mock<LocaleList>()
        val appLocales = mock<LocaleList>()
        whenever(resources.configuration).thenReturn(configuration)
        whenever(configuration.locales).thenReturn(systemLocales)
        whenever(appLocales.get(0)).thenReturn(Locale.forLanguageTag("ar"))
        Mockito.mockStatic(Resources::class.java).use { resourceMethods ->
            Mockito.mockStatic(LocaleList::class.java).use { localeMethods ->
                resourceMethods.`when`<Resources> { Resources.getSystem() }.thenReturn(resources)
                localeMethods.`when`<LocaleList> { LocaleList.getDefault() }.thenReturn(appLocales)
                for (systemLocale in listOf(Locale.CHINA, Locale.UK, Locale.TRADITIONAL_CHINESE)) {
                    whenever(systemLocales.get(0)).thenReturn(systemLocale)
                    assertEquals(systemLocale, Utils.getSysLocale())
                }
            }
        }
    }
}
