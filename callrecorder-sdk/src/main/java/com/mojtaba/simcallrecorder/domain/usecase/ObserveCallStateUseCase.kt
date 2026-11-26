package com.mojtaba.simcallrecorder.domain.usecase

import com.mojtaba.simcallrecorder.domain.repository.CallStateRepository
import javax.inject.Inject

internal class ObserveCallStateUseCase @Inject constructor(
    private val repository: CallStateRepository,
) {
    operator fun invoke() = repository.observeCallState()
}
