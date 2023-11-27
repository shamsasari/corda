package net.corda.detekt.plugins

import io.gitlab.arturbosch.detekt.api.Config
import io.gitlab.arturbosch.detekt.api.RuleSet
import io.gitlab.arturbosch.detekt.api.RuleSetProvider

// When adding new detekt rules, add the new rule to the list of instances below.
class CordaDetektProvider : RuleSetProvider {

    override val ruleSetId: String = "corda-detekt"

    override fun instance(config: Config): RuleSet = RuleSet(
            ruleSetId,
            listOf(
                    // Add new rule instances here
            )
    )
}