package com.agentcall.app.profile

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.database.entity.CallRecordEntity
import com.agentcall.app.data.database.entity.TranscriptMessageEntity
import com.agentcall.app.data.repository.CallRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfileDetailViewModel @Inject constructor(
    private val repository: CallRepository,
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

    private val _renameError = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val renameError: SharedFlow<String> = _renameError.asSharedFlow()

    /**
     * Rename the agent server-side first (the backend key's name is what the
     * ring delivers), then locally. Failures surface a user-safe message and
     * leave the local name untouched — renaming locally alone was what caused
     * duplicate profiles on the next ring.
     */
    fun renameProfile(newName: String) {
        viewModelScope.launch {
            try {
                repository.renameProfile(profileId, newName)
            } catch (e: Exception) {
                _renameError.tryEmit(e.message ?: "Couldn't rename — try again")
            }
        }
    }

    fun getTranscriptForCall(callId: String): Flow<List<TranscriptMessageEntity>> {
        viewModelScope.launch { repository.ensureTranscriptFetched(callId) }
        return repository.getTranscriptForCall(callId)
    }
}
