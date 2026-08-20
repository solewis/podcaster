package com.solewis.podcaster.ui.show

import com.solewis.podcaster.domain.JumpTargetResolver

data class JumpPillUi(
    val episodeId: String,
    /** Index into `ShowViewModel.UiState.items` - already header-adjusted. */
    val itemIndex: Int,
    val intent: JumpTargetResolver.Intent,
    val label: String,
    val secondary: String?
)
