package io.codnect.procyon

import com.intellij.openapi.util.IconLoader

object ProcyonIcons {

    /** The logo of the plugin, also used for its entry in the New Project dialog. */
    @JvmField
    val Logo = load("/META-INF/pluginIcon.svg")

    /** The logo drawn for the 16 pixels of the New Project list; `logo@2x.png` is used on HiDPI screens. */
    @JvmField
    val SmallLogo = load("/icons/logo.png")

    @JvmField
    val Component = load("/icons/component.svg")

    @JvmField
    val ComponentConstructor = load("/icons/component-constructor.svg")

    @JvmField
    val ConfigurationProperties = load("/icons/configuration-properties.svg")

    @JvmField
    val Controller = load("/icons/controller.svg")

    @JvmField
    val Handler = load("/icons/handler.svg")

    /** A controller that is also registered as a component. */
    @JvmField
    val ControllerComponent = load("/icons/component-controller.svg")

    /** A properties struct that is also registered as a component. */
    @JvmField
    val ConfigurationPropertiesComponent = load("/icons/component-configuration-properties.svg")

    private fun load(path: String) = IconLoader.getIcon(path, ProcyonIcons::class.java)
}
