package org.michimusic.core.metadata

import java.text.Normalizer
import java.util.Locale

/**
 * Explicit, bounded SAF rename preflight for a CURRENT parent listing.
 * Passing is evidence for a provider-specific external trial, NOT permission
 * to invoke DocumentsContract.renameDocument, change a URI or update Room.
 */
object MetadataSafRenameSafetyPolicy {
    data class Child(val documentId:String,val name:String)
    data class Input(
        val snapshot:MetadataSnapshot,
        val template:String,
        val proposal:MetadataFilenameToolkit.ProposedRename,
        val sourceDocumentId:String,
        val children:List<Child>,
        val expectedSourceSha256:String,
        val currentSourceSha256:String,
        val currentSourceBytes:Long,
        val provider:MetadataDocumentProvider,
        val listingComplete:Boolean,
        val sameParentVerified:Boolean,
        val exactDocumentGrant:Boolean,
        val providerSupportsRename:Boolean,
        val playbackClosed:Boolean,
        val ownerCurrent:Boolean,
        val journalClear:Boolean,
        val explicitlyConfirmed:Boolean,
    )
    enum class Refusal {
        ACTION_REQUIRED, UNVERIFIED_SELECTION, NOT_SAF, GRANT_MISSING,
        SOURCE_CHANGED, PLAYBACK_ACTIVE, RECOVERY_PENDING, PROVIDER_CAPABILITY_MISSING,
        PARENT_NOT_PROVEN, AMBIGUOUS_LISTING, TARGET_MISSING, ORIGINAL_NAME_CHANGED,
        STALE_PROPOSAL, NO_CHANGE, COLLISION, TOO_MANY_CHILDREN,
    }
    sealed interface Result {
        data class Blocked(val reason:Refusal):Result
        /** No API call or write permission may be derived from this verdict. */
        data class ReadyForProviderSpecificTrial(
            val sourceDocumentId:String,
            val proposedName:String,
            val observedParentCount:Int,
            val originalSha256:String,
        ):Result {
            val mayRenameSource:Boolean get()=false
        }
    }
    private val sha=Regex("[a-f0-9]{64}")
    private fun folded(value:String):String = Normalizer.normalize(value,
        Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun assess(x:Input):Result {
        fun no(reason:Refusal)=Result.Blocked(reason)
        if(!x.explicitlyConfirmed)return no(Refusal.ACTION_REQUIRED)
        if(!x.snapshot.tagsVerified||x.proposal.target!=x.snapshot.target ||
            x.snapshot.target.uri.isBlank())return no(Refusal.UNVERIFIED_SELECTION)
        if(x.provider!=MetadataDocumentProvider.SAF)return no(Refusal.NOT_SAF)
        if(!x.exactDocumentGrant)return no(Refusal.GRANT_MISSING)
        if(!x.ownerCurrent||x.currentSourceBytes<=0||!sha.matches(x.expectedSourceSha256)||
            x.expectedSourceSha256!=x.currentSourceSha256)return no(Refusal.SOURCE_CHANGED)
        if(!x.playbackClosed)return no(Refusal.PLAYBACK_ACTIVE)
        if(!x.journalClear)return no(Refusal.RECOVERY_PENDING)
        if(!x.providerSupportsRename)return no(Refusal.PROVIDER_CAPABILITY_MISSING)
        if(!x.sameParentVerified||!x.listingComplete)return no(Refusal.PARENT_NOT_PROVEN)
        if(x.children.size>4096)return no(Refusal.TOO_MANY_CHILDREN)
        if(x.sourceDocumentId.isBlank()||x.children.any{it.documentId.isBlank()||it.name.isBlank()}||
            x.children.map{it.documentId}.distinct().size!=x.children.size)
            return no(Refusal.AMBIGUOUS_LISTING)
        val self=x.children.singleOrNull{it.documentId==x.sourceDocumentId}
            ?:return no(Refusal.TARGET_MISSING)
        if(self.name!=x.proposal.before)return no(Refusal.ORIGINAL_NAME_CHANGED)
        val recomputed=MetadataFilenameToolkit.proposeRename(
            x.snapshot,x.proposal.before,x.template)
        if(recomputed !is MetadataFilenameToolkit.Result.Success ||
            recomputed.value!=x.proposal)return no(Refusal.STALE_PROPOSAL)
        if(folded(x.proposal.after)==folded(x.proposal.before))return no(Refusal.NO_CHANGE)
        if(x.children.any{it.documentId!=x.sourceDocumentId &&
                folded(it.name)==folded(x.proposal.after)})return no(Refusal.COLLISION)
        return Result.ReadyForProviderSpecificTrial(
            x.sourceDocumentId,x.proposal.after,x.children.size,x.currentSourceSha256)
    }
}
