package com.rokid.context.phone.context

/** Builds spec §8 messages with increasing seq and a fixed epoch, and delivers them like the provider would. */
class FakeLiveFit(private val intake: WatchMotionIntake, var epoch: Long = 7193856231L, private val pkg: String = "com.livear.fit") {
    var seq = 0L

    fun json(state: String = "WALKING", confidence: Double = 0.9, seq: Long = ++this.seq, epoch: Long = this.epoch, v: Int = 1) =
        """{"v":$v,"epoch":$epoch,"seq":$seq,"state":"$state","confidence":$confidence,"changedAtMs":1761012345678,"sentAtMs":1761012345900,"source":"galaxy_watch"}"""

    fun send(state: String = "WALKING", confidence: Double = 0.9, seq: Long = ++this.seq, epoch: Long = this.epoch) =
        intake.accept(pkg, { true }, json(state, confidence, seq, epoch))
}
