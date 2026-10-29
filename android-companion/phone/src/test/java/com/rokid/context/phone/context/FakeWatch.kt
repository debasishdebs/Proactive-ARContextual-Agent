package com.rokid.context.phone.context

/** Builds watch messages with increasing seq and a fixed epoch, and delivers them like the Data Layer listener would. */
class FakeWatch(private val intake: WatchMotionIntake, var epoch: Long = 7193856231L) {
    var seq = 0L

    fun json(state: String = "WALKING", confidence: Double = 0.9, seq: Long = ++this.seq, epoch: Long = this.epoch, v: Int = 1) =
        """{"v":$v,"epoch":$epoch,"seq":$seq,"state":"$state","confidence":$confidence,"changedAtMs":1761012345678,"sentAtMs":1761012345900,"source":"pca_watch"}"""

    fun send(state: String = "WALKING", confidence: Double = 0.9, seq: Long = ++this.seq, epoch: Long = this.epoch) =
        intake.accept(json(state, confidence, seq, epoch))
}
