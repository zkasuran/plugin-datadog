@PluginSubGroup(
    title = "Datadog monitors",
    description = "A task to list monitors and a trigger that starts a flow when monitors change state.",
    categories = {
        PluginSubGroup.PluginCategory.DATA,
        PluginSubGroup.PluginCategory.INFRASTRUCTURE
    }
)
package io.kestra.plugin.datadog.monitors;

import io.kestra.core.models.annotations.PluginSubGroup;
