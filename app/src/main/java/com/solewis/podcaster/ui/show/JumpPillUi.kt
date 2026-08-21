package com.solewis.podcaster.ui.show

import com.solewis.podcaster.domain.JumpTargetResolver

data class JumpPillUi(
    val episodeId: String,
    /** Index into `ShowViewModel.UiState.episodes`, in the same (sorted) order the episode list
     * itself renders in. */
    val itemIndex: Int,
    val intent: JumpTargetResolver.Intent,
    val label: String,
    val secondary: String?
)
