package com.example.taskdemo.feature.home.presentation.dashboard

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.taskdemo.commons.util.Result
import com.example.taskdemo.commons.util.UiText
import com.example.taskdemo.commons.util.loadstate.LoadState
import com.example.taskdemo.commons.util.loadstate.LoadStates
import com.example.taskdemo.commons.util.loadstate.LoadType
import com.example.taskdemo.core.data.persistence.PersistentStore
import com.example.taskdemo.core.designsystem.component.text.SimpleEditTextState
import com.example.taskdemo.core.di.RepositorySource
import com.example.taskdemo.core.di.RepositorySources
import com.example.taskdemo.extensions.ifDebug
import com.example.taskdemo.feature.home.domain.model.DashboardData
import com.example.taskdemo.feature.home.domain.model.OpenAppLink
import com.example.taskdemo.feature.home.domain.repository.InOpenAppRepository
import com.example.taskdemo.feature.home.presentation.util.GreetingMessage
import com.example.taskdemo.feature.home.presentation.util.TimeOfDay
import com.example.taskdemo.feature.home.presentation.util.getRandomDescription
import com.example.taskdemo.feature.home.presentation.util.getRandomEmoji
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @RepositorySource(RepositorySources.Fake)
    private val repository: InOpenAppRepository,
    private val persistentStore: PersistentStore,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val tokenInputState by lazy {
        SimpleEditTextState(
            initialValue = persistentStore.deviceToken,
            validator = { value -> value.isNotBlank() },
            errorFor = { value ->
                when {
                    value.isBlank() -> "Token is required"
                    else -> ""
                }
            }
        )
    }

    private val viewModelState = MutableStateFlow(DashboardState())

    private val _uiEvent = MutableSharedFlow<DashboardUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    val accept: (DashboardUiAction) -> Unit

    val uiState: StateFlow<DashboardUiState> = viewModelState
        .map(DashboardState::toDashboardUiState)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = viewModelState.value.toDashboardUiState()
        )

    private var dashboardDataFetchJob: Job? = null

    init {
        repository.dashboardStream()
            .distinctUntilChanged()
            .onEach { remoteDashboardData ->
                viewModelState.update { state ->
                    state.copy(
                        dashboardData = remoteDashboardData
                    )
                }
            }
            .onStart { refreshInternal() }
            .launchIn(viewModelScope)

        accept = { uiAction -> onUiAction(uiAction) }
    }

    private fun onUiAction(action: DashboardUiAction) {
        when (action) {
            is DashboardUiAction.OnTabSelected -> {
                handleTabSelectedInternal(action.tab)
            }
            DashboardUiAction.SubmitAccessToken -> {
                viewModelScope.launch {
                    persistentStore.setDeviceToken(tokenInputState.text.value)
                    sendEvent(DashboardUiEvent.ShowToast(UiText.DynamicString("Token saved")))
                }
            }
            DashboardUiAction.Refresh -> {
                refreshInternal()
            }
        }
    }

    private fun refreshInternal() {
        getDashboardDataInternal()
    }

    private fun handleTabSelectedInternal(tab: DashboardTab) {
        viewModelState.update { state ->
            state.copy(
                currentDashboardTab = tab
            )
        }
    }

    private fun getDashboardDataInternal() {
        if (dashboardDataFetchJob?.isActive == true) {
            val t = IllegalStateException("A refresh job is already active. Ignoring refresh.")
            ifDebug { Timber.w(t) }
            return
        }

        setLoading(LoadType.REFRESH, LoadState.Loading())
        dashboardDataFetchJob?.cancel(CancellationException("New request"))
        dashboardDataFetchJob = viewModelScope.launch {
            when (val result = repository.refreshDashboard()) {
                Result.Loading -> {}

                is Result.Error -> {
                    setLoading(LoadType.REFRESH, LoadState.Error(result.exception))
                }

                is Result.Success -> {
                    setLoading(LoadType.REFRESH, LoadState.NotLoading.Complete)
                    // The data is updated from the stream
                }
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun setLoading(
        loadType: LoadType,
        loadState: LoadState
    ) {
        val newLoadState = viewModelState.value.loadState.modifyState(loadType, loadState)
        viewModelState.update { it.copy(loadState = newLoadState) }
    }

    private fun sendEvent(newEvent: DashboardUiEvent) = viewModelScope.launch {
        _uiEvent.emit(newEvent)
    }

}

private data class DashboardState(
    val loadState: LoadStates = LoadStates.IDLE,
    val dashboardData: DashboardData? = null,

    /* Other UI components */
    val currentDashboardTab: DashboardTab = DashboardTab.TopLinks,
) {
    fun toDashboardUiState(): DashboardUiState {
        return DashboardUiState(
            loadState = loadState,
            dashboardData = dashboardData,

            currentDashboardTab = currentDashboardTab,
        )
    }
}

data class DashboardUiState(
    val loadState: LoadStates = LoadStates.IDLE,
    val dashboardData: DashboardData? = null,

    /* Other UI components */
    val currentDashboardTab: DashboardTab = DashboardTab.TopLinks,
) {
    fun toUiModels(): List<DashboardUiModel> {
        val timeOfDay = TimeOfDay.current
        val greetingMessage = GreetingMessage(
            title = timeOfDay.greetingMessage,
            description = getRandomDescription(), /* "It's time to something awesome! 🤩" */
            leadingEmojiText = timeOfDay.emoji,
            trailingEmojiText = getRandomEmoji(),
        )

        return mutableListOf<DashboardUiModel>().apply {
            val header = DashboardUiModel.DashboardHeader(
                userProfile = UserProfile(
                    fullName = "John Doe",
                    shortName = "John",
                    profileImage = null,
                ),
                greetingMessage = greetingMessage,
                dashboardData = dashboardData ?: DashboardData.EMPTY,
            )
            add(header)

            val tabLayout = DashboardUiModel.DashboardTabLayout(
                selectedTab = currentDashboardTab,
                dashboardData = dashboardData ?: DashboardData.EMPTY
            )
            add(tabLayout)

            val footer = DashboardUiModel.DashboardSupportFooter(
                whatsAppSupportNumber = dashboardData?.supportWhatsappNumber ?: ""
            )
            add(footer)
        }
    }
}

interface DashboardUiModel {
    data class DashboardHeader(
        val userProfile: UserProfile,
        val greetingMessage: GreetingMessage,
        val dashboardData: DashboardData,
    ) : DashboardUiModel

    data class DashboardTabLayout(
        val selectedTab: DashboardTab,
        val dashboardData: DashboardData
    ) : DashboardUiModel

    data class DashboardSupportFooter(
        val whatsAppSupportNumber: String,
    ) : DashboardUiModel
}

fun DashboardUiModel.DashboardTabLayout.getLinksForActiveTab(): List<OpenAppLink> =
    when (this.selectedTab) {
        DashboardTab.TopLinks -> this.dashboardData.topLinks
        DashboardTab.RecentLinks -> this.dashboardData.recentLinks
        DashboardTab.FavoriteLinks -> this.dashboardData.favouriteLinks
    }

interface DashboardUiAction {
    data class OnTabSelected(val tab: DashboardTab) : DashboardUiAction
    data object SubmitAccessToken : DashboardUiAction
    data object Refresh : DashboardUiAction
}

interface DashboardUiEvent {
    data class ShowToast(val message: UiText) : DashboardUiEvent
    data class ShowSnack(val message: UiText) : DashboardUiEvent
}

enum class DashboardTab(val id: String) {
    TopLinks("top_links"),
    RecentLinks("recent_links"),
    FavoriteLinks("fav_links")
}

data class UserProfile(
    val fullName: String,
    val shortName: String,
    val profileImage: String?,
)