package com.agentcall.app.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.call.agentSlug
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.CallerTuneManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    private val repository: CallRepository,
    val callerTuneManager: CallerTuneManager,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val profileId: String = savedStateHandle["profileId"] ?: "ai-agent"

    val profile: StateFlow<AiProfileEntity?> = repository
        .getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        .let { flow ->
            val result = MutableStateFlow<AiProfileEntity?>(null)
            viewModelScope.launch {
                flow.collect { profiles ->
                    result.value = profiles.find { it.id == profileId }
                }
            }
            result.asStateFlow()
        }

    val calls: StateFlow<List<CallRecordEntity>> = repository
        .getCallsForProfile(profileId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun renameProfile(newName: String) {
        viewModelScope.launch { repository.renameProfile(profileId, newName) }
    }

    // ── Per-agent ringtone (backlog item 7) ─────────────────────────
    // The ring path resolves from CallerTuneManager (synchronous); the Room
    // profile columns mirror it for display/backup so the two never diverge.
    fun setRingtone(uri: String?, label: String?) {
        val key = profileId.agentSlug()
        if (uri == null) {
            callerTuneManager.resetAgentUri(key)
        } else {
            callerTuneManager.setAgentUri(key, android.net.Uri.parse(uri), label ?: "Custom ringtone")
        }
        viewModelScope.launch { repository.setProfileRingtone(profileId, uri, label) }
    }

    // ── Per-agent quick replies (backlog item 9) ────────────────────
    fun setQuickReplies(chips: List<String>) {
        viewModelScope.launch { repository.setProfileQuickReplies(profileId, chips) }
    }

    fun parseQuickReplies(raw: String?): List<String> = CallRepository.parseQuickReplies(raw)

    fun getTranscriptForCall(callId: String): Flow<List<TranscriptMessageEntity>> {
        viewModelScope.launch { repository.ensureTranscriptFetched(callId) }
        return repository.getTranscriptForCall(callId)
    }
}
