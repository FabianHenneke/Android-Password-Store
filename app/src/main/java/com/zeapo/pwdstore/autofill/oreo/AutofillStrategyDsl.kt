package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import androidx.annotation.RequiresApi
import com.github.ajalt.timberkt.d

@DslMarker
annotation class AutofillDsl

@RequiresApi(Build.VERSION_CODES.O)
interface FieldMatcher {
    fun match(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>?

    @AutofillDsl
    class Builder {
        private var takeSingle: (FormField.(List<FormField>) -> Boolean)? = null
        private val tieBreakersSingle: MutableList<FormField.() -> Boolean> = mutableListOf()

        private var takePair: (Pair<FormField, FormField>.(List<FormField>) -> Boolean)? = null
        private var tieBreakersPair: MutableList<Pair<FormField, FormField>.() -> Boolean> =
            mutableListOf()

        fun takeSingle(block: FormField.(alreadyMatched: List<FormField>) -> Boolean) {
            check(takeSingle == null && takePair == null) { "Every block can only have at most one take{Single,Pair} block" }
            takeSingle = block
        }

        fun breakTieOnSingle(block: FormField.() -> Boolean) {
            check(takeSingle != null) { "Every block needs a takeSingle block before a breakTieOnSingle block" }
            check(takePair == null) { "takePair cannot be mixed with breakTieOnSingle" }
            tieBreakersSingle.add(block)
        }

        fun takePair(block: Pair<FormField, FormField>.(alreadyMatched: List<FormField>) -> Boolean) {
            check(takeSingle == null && takePair == null) { "Every block can only have at most one take{Single,Pair} block" }
            takePair = block
        }

        fun breakTieOnPair(block: Pair<FormField, FormField>.() -> Boolean) {
            check(takePair != null) { "Every block needs a takePair block before a breakTieOnPair block" }
            check(takeSingle == null) { "takeSingle cannot be mixed with breakTieOnPair" }
            tieBreakersPair.add(block)
        }

        fun build(): FieldMatcher {
            val takeSingle = takeSingle
            val takePair = takePair
            return when {
                takeSingle != null -> SingleFieldMatcher(takeSingle, tieBreakersSingle)
                takePair != null -> PairOfFieldsMatcher(takePair, tieBreakersPair)
                else -> throw IllegalArgumentException("Every block needs a take{Single,Pair} block")
            }
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class SingleFieldMatcher(
    private val take: (FormField, List<FormField>) -> Boolean,
    private val tieBreakers: List<(FormField) -> Boolean>
) : FieldMatcher {

    @AutofillDsl
    class Builder {
        private var takeSingle: (FormField.(List<FormField>) -> Boolean)? = null
        private val tieBreakersSingle: MutableList<FormField.() -> Boolean> = mutableListOf()

        fun takeSingle(block: FormField.(alreadyMatched: List<FormField>) -> Boolean) {
            check(takeSingle == null) { "Every block can only have at most one takeSingle block" }
            takeSingle = block
        }

        fun breakTieOnSingle(block: FormField.() -> Boolean) {
            check(takeSingle != null) { "Every block needs a takeSingle block before a breakTieOnSingle block" }
            tieBreakersSingle.add(block)
        }

        fun build() = SingleFieldMatcher(
            takeSingle
                ?: throw IllegalArgumentException("Every block needs a take{Single,Pair} block"),
            tieBreakersSingle
        )
    }

    override fun match(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>? {
        fields.filter { take(it, alreadyMatched) }.let { contestants ->
            var current = contestants
            for (tieBreaker in tieBreakers) {
                // Successively filter matched fields via tie breakers...
                val new = current.filter { tieBreaker(it) }
                // skipping those tie breakers that are not satisfied for any remaining field...
                if (new.isEmpty()) continue
                // and return if the available options have been narrowed to a single field.
                if (new.size == 1) return listOf(new.single())
                current = new
            }
            return null
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class PairOfFieldsMatcher(
    private val take: (Pair<FormField, FormField>, List<FormField>) -> Boolean,
    private val tieBreakers: List<(Pair<FormField, FormField>) -> Boolean>
) : FieldMatcher {

    override fun match(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>? {
        fields.zipWithNext().filter { it.first directlyPrecedes it.second }
            .filter { take(it, alreadyMatched) }.let { contestants ->
                var current = contestants
                for (tieBreaker in tieBreakers) {
                    val new = current.filter { tieBreaker(it) }
                    if (new.isEmpty()) continue
                    if (new.size == 1) return new.single().toList()
                    current = new
                }
                return null
            }
    }
}


@RequiresApi(Build.VERSION_CODES.O)
class AutofillRule private constructor(
    private val matchers: List<AutofillRuleMatcher>, private val applyInSingleOriginMode: Boolean
) {

    data class AutofillRuleMatcher(
        val type: FillableFieldType, val matcher: FieldMatcher, val optional: Boolean
    )

    enum class FillableFieldType {
        Username, CurrentPassword, NewPassword, GenericPassword,
    }

    @AutofillDsl
    class Builder(private val applyInSingleOriginMode: Boolean) {
        private val matchers = mutableListOf<AutofillRuleMatcher>()

        fun username(optional: Boolean = false, block: SingleFieldMatcher.Builder.() -> Unit) {
            require(matchers.none { it.type == FillableFieldType.Username }) { "Every rule block can only have at most one username block" }
            matchers.add(
                AutofillRuleMatcher(
                    type = FillableFieldType.Username,
                    matcher = SingleFieldMatcher.Builder().apply(block).build(),
                    optional = optional
                )
            )
        }

        fun currentPassword(optional: Boolean = false, block: FieldMatcher.Builder.() -> Unit) {
            require(matchers.none { it.type == FillableFieldType.GenericPassword }) { "Every rule block can only have either genericPassword or {current,new}Password blocks" }
            matchers.add(
                AutofillRuleMatcher(
                    type = FillableFieldType.CurrentPassword,
                    matcher = FieldMatcher.Builder().apply(block).build(),
                    optional = optional
                )
            )
        }

        fun newPassword(optional: Boolean = false, block: FieldMatcher.Builder.() -> Unit) {
            require(matchers.none { it.type == FillableFieldType.GenericPassword }) { "Every rule block can only have either genericPassword or {current,new}Password blocks" }
            matchers.add(
                AutofillRuleMatcher(
                    type = FillableFieldType.NewPassword,
                    matcher = FieldMatcher.Builder().apply(block).build(),
                    optional = optional
                )
            )
        }

        fun genericPassword(optional: Boolean = false, block: FieldMatcher.Builder.() -> Unit) {
            require(matchers.none {
                it.type in listOf(
                    FillableFieldType.CurrentPassword, FillableFieldType.NewPassword
                )
            }) { "Every rule block can only have either genericPassword or {current,new}Password blocks" }
            matchers.add(
                AutofillRuleMatcher(
                    type = FillableFieldType.GenericPassword,
                    matcher = FieldMatcher.Builder().apply(block).build(),
                    optional = optional
                )
            )
        }

        fun build(): AutofillRule {
            if (applyInSingleOriginMode) {
                require(matchers.none { it.matcher is PairOfFieldsMatcher }) { "Rules with applyInSingleOriginMode set to true must only match single fields" }
                require(matchers.filter { it.type != FillableFieldType.Username }.size <= 1) { "Rules with applyInSingleOriginMode set to true must only match at most one password field" }
            }
            return AutofillRule(matchers, applyInSingleOriginMode)
        }
    }

    // FIXME
    private fun passesOriginCheck(
        scenario: AutofillScenario<FormField>, singleOriginMode: Boolean
    ): Boolean {
        return if (singleOriginMode) {
            // In single origin mode, only the browsers URL bar (which is never filled) should have
            // a webOrigin.
            scenario.allFields.all { it.webOrigin == null }
        } else {
            // In apps or browsers in multi origin mode, every field in a dataset has to belong to
            // the same (possibly null) origin.
            scenario.allFields.map { it.webOrigin }.toSet().size == 1
        }.also {
            // FIXME
            if (!it) d { "Rule failed origin check" }
        }
    }

    fun apply(
        allPassword: List<FormField>, allUsername: List<FormField>, singleOriginMode: Boolean
    ): AutofillScenario<FormField>? {
        if (singleOriginMode && !applyInSingleOriginMode) return null
        val scenarioBuilder = AutofillScenario.Builder<FormField>()
        val alreadyMatched = mutableListOf<FormField>()
        for ((type, matcher, optional) in matchers) {
            val matchResult = when (type) {
                FillableFieldType.Username -> matcher.match(allUsername, alreadyMatched)
                else -> matcher.match(allPassword, alreadyMatched)
            } ?: if (optional) continue else return null
            when (type) {
                FillableFieldType.Username -> {
                    check(matchResult.size == 1 && scenarioBuilder.username == null)
                    scenarioBuilder.username = matchResult.single()
                    // E.g. hidden username fields can be saved but not filled.
                    scenarioBuilder.fillUsername = scenarioBuilder.username?.isFillable == true
                }
                FillableFieldType.CurrentPassword -> scenarioBuilder.currentPassword.addAll(
                    matchResult
                )
                FillableFieldType.NewPassword -> scenarioBuilder.newPassword.addAll(matchResult)
                FillableFieldType.GenericPassword -> scenarioBuilder.genericPassword.addAll(
                    matchResult
                )
            }
            alreadyMatched.addAll(matchResult)
        }
        return scenarioBuilder.build().takeIf {
            passesOriginCheck(it, singleOriginMode = singleOriginMode)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class AutofillStrategy(private val rules: List<AutofillRule>) {

    @AutofillDsl
    class Builder {
        private val rules: MutableList<AutofillRule> = mutableListOf()

        fun rule(
            applyInSingleOriginMode: Boolean = false, block: AutofillRule.Builder.() -> Unit
        ) = AutofillRule.Builder(applyInSingleOriginMode).apply(block).build()

        fun build() = AutofillStrategy(rules)
    }

    fun apply(fields: List<FormField>, multiOriginSupport: Boolean): AutofillScenario<FormField>? {
        val possiblePasswordFields =
            fields.filter { it.passwordCertainty >= CertaintyLevel.Possible }
        val possibleUsernameFields =
            fields.filter { it.usernameCertainty >= CertaintyLevel.Possible }
        // Return the result of the first rule that matches
        for (rule in rules) {
            return rule.apply(possiblePasswordFields, possibleUsernameFields, multiOriginSupport)
                ?: continue
        }
        return null
    }
}


fun strategy(block: AutofillStrategy.Builder.() -> Unit) =
    AutofillStrategy.Builder().apply(block).build()

