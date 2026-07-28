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

    fun getTranscriptForCall(callId: String): Flow<List<TranscriptMessageEntity>> {
        viewModelScope.launch { repository.ensureTranscriptFetched(callId) }
        return repository.getTranscriptForCall(callId)
    }
}
