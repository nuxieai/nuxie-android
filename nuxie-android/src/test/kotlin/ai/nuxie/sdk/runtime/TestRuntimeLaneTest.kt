package ai.nuxie.sdk.runtime

/** The presentation-test fake must obey the same lifecycle as the native lane. */
class TestRuntimeLaneTest : RuntimeLaneContract() {
    internal override fun createLane(): NuxieRuntimeLane = TestRuntimeLane()
}
