// Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.ui.layout.panel
import software.aws.toolkits.jetbrains.settings.LambdaSettings
import software.aws.toolkits.resources.message

class LambdaSettingsConfigurable(private val project: Project) : BoundConfigurable(message("aws.settings.lambda.configurable.title")), SearchableConfigurable {
    private val lambdaSettings get() = LambdaSettings.getInstance(project)
    override fun getId() = "aws.lambda"
    override fun createPanel() = panel {
        row {
            checkBox(
                message("aws.settings.sam.show_all_gutter_icons"),
                lambdaSettings::showAllHandlerGutterIcons,
                message("aws.settings.sam.show_all_gutter_icons_tooltip")
            )

        }
        row {
            label(message("aws.settings.sam.typescript.title"))
        }
        row {
            checkBox(
                message("aws.settings.sam.typescript.no_overwrite_config"),
                lambdaSettings::preventTSConfigOverwrite,
                message("aws.settings.sam.typescript.no_overwrite_config_tooltip")
            )
        }
    }
}
