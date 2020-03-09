package com.zeapo.pwdstore.autofill.oreo

import android.os.Build
import androidx.annotation.RequiresApi

@DslMarker
annotation class AutofillDsl

enum class AutofillScenarioType {
    Fill,
    Generate,
}

@RequiresApi(Build.VERSION_CODES.O)
data class AutofillScenario(val type: AutofillScenarioType, val username: FormField?, val password: List<FormField>)

@RequiresApi(Build.VERSION_CODES.O)
interface FieldMatcher {
    fun apply(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>?

    @AutofillDsl
    class Builder : SingleFieldMatcher.Builder() {

        fun takePair(block: Pair<FormField, FormField>.(alreadyMatched: List<FormField>) -> Boolean) {
            check(takeSingle == null && takePair == null) { "Every block can only have at most one take{Single,Pair} block" }
            takePair = block
        }

        fun breakTieOnPair(block: Pair<FormField, FormField>.() -> Boolean) {
            check(takePair != null) { "Every block needs a takePair block before a breakTieOnPair block" }
            check(takeSingle == null) { "takeSingle cannot be mixed with breakTieOnPair" }
            tieBreakersPair.add(block)
        }
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class SingleFieldMatcher(
    private val take: (FormField, List<FormField>) -> Boolean,
    private val tieBreakers: List<(FormField) -> Boolean>
) : FieldMatcher {

    @AutofillDsl
    open class Builder {
        protected var takeSingle: (FormField.(List<FormField>) -> Boolean)? = null
        private val tieBreakersSingle: MutableList<FormField.() -> Boolean> = mutableListOf()

        protected var takePair: (Pair<FormField, FormField>.(List<FormField>) -> Boolean)? = null
        protected var tieBreakersPair: MutableList<Pair<FormField, FormField>.() -> Boolean> =
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

    override fun apply(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>? {
        fields.filter { take(it, alreadyMatched) }.let { contestants ->
            var current = contestants
            for (tieBreaker in tieBreakers) {
                val new = current.filter { tieBreaker(it) }
                if (new.isEmpty()) continue
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

    override fun apply(fields: List<FormField>, alreadyMatched: List<FormField>): List<FormField>? {
        fields.zipWithNext().filter { it.first precedes it.second }
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
class AutofillRule(
    val type: AutofillScenarioType,
    private val passwordMatcher: FieldMatcher,
    private val usernameMatcher: SingleFieldMatcher?,
    private val takePasswordFirst: Boolean,
    val stopOnMatch: Boolean,
    val requiresMultiOriginSupport: Boolean
) {

    @AutofillDsl
    class Builder(private val type: AutofillScenarioType) {
        private var passwordMatcher: FieldMatcher? = null
        private var usernameMatcher: SingleFieldMatcher? = null
        private var takePasswordFirst = true

        var stopOnMatch = false
        var requiresMultiOriginSupport = true

        fun password(block: FieldMatcher.Builder.() -> Unit) {
            check(passwordMatcher == null) { "Every {fill,generate}Rule block can only have one password block" }
            passwordMatcher = FieldMatcher.Builder().apply(block).build()
        }

        fun username(block: SingleFieldMatcher.Builder.() -> Unit) {
            check(usernameMatcher == null) { "Every {fill,generate}Rule block can only have one username block" }
            if (passwordMatcher == null) takePasswordFirst = false
            usernameMatcher =
                SingleFieldMatcher.Builder().apply(block).build() as SingleFieldMatcher
        }

        fun build() = AutofillRule(
            type,
            passwordMatcher
                ?: throw IllegalArgumentException("Every {fill,generate}Rule block needs a password block"),
            usernameMatcher,
            takePasswordFirst,
            stopOnMatch,
            requiresMultiOriginSupport
        )
    }

    fun apply(allPassword: List<FormField>, allUsername: List<FormField>): AutofillScenario? {
        val password: List<FormField>
        val username: FormField?
        if (takePasswordFirst) {
            password = passwordMatcher.apply(allPassword, emptyList()) ?: return null
            username = usernameMatcher?.apply(allUsername, password)?.single()
        } else {
            username = usernameMatcher?.apply(allUsername, emptyList())?.single()
            password = passwordMatcher.apply(allPassword, listOfNotNull(username)) ?: return null
        }
        return AutofillScenario(type, username, password)
    }
}

@RequiresApi(Build.VERSION_CODES.O)
class AutofillStrategy(private val rules: List<AutofillRule>) {

    @AutofillDsl
    class Builder {
        private val rules: MutableList<AutofillRule> = mutableListOf()

        fun fillRule(block: AutofillRule.Builder.() -> Unit): AutofillRule =
            AutofillRule.Builder(AutofillScenarioType.Fill).apply(block).build()

        fun generateRule(block: AutofillRule.Builder.() -> Unit): AutofillRule =
            AutofillRule.Builder(AutofillScenarioType.Generate).apply(block).build()

        fun build() = AutofillStrategy(rules)
    }

    fun apply(fields: List<FormField>, multiOriginSupport: Boolean): List<AutofillScenario> {
        val possiblePasswordFields =
            fields.filter { it.passwordCertainty > CertaintyLevel.Possible }
        val possibleUsernameFields =
            fields.filter { it.usernameCertainty > CertaintyLevel.Possible }
        var fillMatch: AutofillScenario? = null
        var generateMatch: AutofillScenario? = null

        loop@ for (rule in rules) {
            if (!multiOriginSupport && rule.requiresMultiOriginSupport) continue@loop
            if (rule.type == AutofillScenarioType.Fill) continue@loop
            if (rule.type == AutofillScenarioType.Generate) continue@loop
            val result = rule.apply(possiblePasswordFields, possibleUsernameFields) ?: continue@loop
            when (result.type) {
                AutofillScenarioType.Fill -> fillMatch = result
                AutofillScenarioType.Generate -> generateMatch = result
            }
            if (rule.stopOnMatch) break@loop
        }
        return listOfNotNull(fillMatch, generateMatch)
    }
}


fun strategy(block: AutofillStrategy.Builder.() -> Unit) =
    AutofillStrategy.Builder().apply(block).build()

