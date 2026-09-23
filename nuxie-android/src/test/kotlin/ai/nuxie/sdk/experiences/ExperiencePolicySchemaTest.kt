package ai.nuxie.sdk.experiences

import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class ExperiencePolicySchemaTest {
    private fun validate(source: String) = ExperiencePolicySchema.validate(Json.parseToJsonElement(source))

    @Test fun aGoalLessExperienceCanBeAdmitted() {
        validate("""{"entry":{"trigger":{"type":"api"},"frequency":{"type":"every_match"}},"exitWhenAny":[]}""")
    }

    @Test fun conversionWindowHasAnExplicitUpperBound() {
        fun policy(days: Int) = """{"entry":{"trigger":{"type":"api"},"frequency":{"type":"one_time"}},"goal":{"criterion":{"type":"event","eventName":"completed"},"attribution":{"basis":"first_shown","window":{"amount":$days,"unit":"day"}}},"exitWhenAny":[{"type":"goal_met"}]}"""
        validate(policy(90))
        assertThrows(JourneyReleaseAuthenticationException::class.java) { validate(policy(91)) }
    }

    @Test fun goalExitCannotInventAConversionGoal() {
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            validate("""{"entry":{"trigger":{"type":"api"},"frequency":{"type":"one_time"}},"exitWhenAny":[{"type":"goal_met"}]}""")
        }
    }

    @Test fun retiredFrequencyAliasesAreRejected() {
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            validate("""{"entry":{"trigger":{"type":"api"},"frequency":{"type":"every_time"}},"exitWhenAny":[]}""")
        }
    }

    @Test fun goalsRejectMutableStateButAcceptOccurrenceEvidence() {
        fun policy(expr: String) = """{"entry":{"trigger":{"type":"api"},"frequency":{"type":"one_time"}},"goal":{"criterion":{"type":"event","eventName":"purchased","condition":{"ir_version":1,"expr":$expr}},"attribution":{"basis":"entry","window":{"amount":1,"unit":"day"}}},"exitWhenAny":[]}"""
        validate(policy("""{"type":"Pred","op":"eq","key":"product_id","value":{"type":"String","value":"premium"}}"""))
        for (node in listOf(
            """{"type":"User","op":"eq","key":"plan","value":{"type":"String","value":"pro"}}""",
            """{"type":"Feature","op":"has","id":"premium"}""",
            """{"type":"Subscription","op":"active"}""",
            """{"type":"Segment","op":"is_member","id":"paid"}""",
            """{"type":"Events.Exists","name":"purchased"}""",
            """{"type":"Response.Field","key":"answer"}""",
        )) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                validate(policy("""{"type":"And","args":[{"type":"Bool","value":true},$node]}"""))
            }
        }
    }

    @Test fun offerHandlersAreNotConversionEvidence() {
        for (event in listOf("\$offer_already_entitled", "\$offer_access_unknown", "\$journey_milestone")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                validate("""{"entry":{"trigger":{"type":"event","eventName":"$event"},"frequency":{"type":"one_time"}},"exitWhenAny":[]}""")
            }
        }
    }
}
