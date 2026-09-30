package io.codnect.procyon.component

import com.goide.psi.GoTypeSpec
import io.codnect.procyon.ProcyonBundle
import io.codnect.procyon.ProcyonIcons
import io.codnect.procyon.config.ProcyonProperties
import io.codnect.procyon.http.EndpointMappers
import javax.swing.Icon

/** What a registered component is, so that its type and its `component.Register` show the same icon. */
internal object Roles {

    /** Returns the icon of a registered component of this type: controller, properties struct, or plain. */
    fun registeredIcon(spec: GoTypeSpec?): Icon = when {
        spec != null && EndpointMappers.isEndpointMapper(spec) -> ProcyonIcons.ControllerComponent
        spec != null && ProcyonProperties.prefixOf(spec) != null -> ProcyonIcons.ConfigurationPropertiesComponent
        else -> ProcyonIcons.Component
    }

    /** Returns a name for the role, for tooltips. */
    fun roleName(spec: GoTypeSpec?): String = when {
        spec != null && EndpointMappers.isEndpointMapper(spec) -> ProcyonBundle.message("role.controller")
        spec != null && ProcyonProperties.prefixOf(spec) != null -> ProcyonBundle.message("role.properties")
        else -> ProcyonBundle.message("role.component")
    }
}
