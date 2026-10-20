package com.floatclip.app.sync

import com.floatclip.share.ClipboardSync

/** Replays durable user intent without treating a skipped intermediate toggle as a new grant. */
object SyncPolicy {
    fun mayShare(policy: SyncPreferences.Policy,capturedGeneration: String?)=policy.enabled&&policy.sending&&capturedGeneration!=null&&capturedGeneration==policy.sendGeneration
    fun apply(prefs: SyncPreferences,engine: ClipboardSync): SyncPreferences.Policy {
        val p=prefs.policy()
        val sendChanged=p.sendGeneration!=p.appliedSend
        val receiveChanged=p.receiveGeneration!=p.appliedReceive
        val applyChanged=p.applyGeneration!=p.appliedApply
        if(sendChanged)engine.configure(p.enabled,false,p.receiving,p.applying)
        if(receiveChanged)engine.renewReceiveGrant()
        if(applyChanged)engine.invalidateApplications()
        if(sendChanged||receiveChanged||applyChanged||engine.enabled()!=p.enabled||engine.sending()!=p.sending||engine.receiving()!=p.receiving||engine.applying()!=p.applying)
            engine.configure(p.enabled,p.sending,p.receiving,p.applying)
        if(sendChanged||receiveChanged||applyChanged)prefs.applied(p)
        return p
    }
}
