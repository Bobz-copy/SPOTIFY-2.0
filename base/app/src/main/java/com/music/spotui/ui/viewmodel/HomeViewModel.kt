package com.music.spotui.ui.viewmodel

import com.music.spotui.di.Graph
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.spotui.data.api.Response
import com.music.spotui.data.entity.HomeFeedModel
import com.music.spotui.ui.repository.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class HomeViewModel constructor(private val repository: AppRepository = Graph.repository)  : ViewModel() {

    private val _home : MutableStateFlow<Response<HomeFeedModel>> = MutableStateFlow(Response.Loading())
    val home : StateFlow<Response<HomeFeedModel>> = _home

    /** Selected home chip ("" = All). */
    val facet = androidx.compose.runtime.mutableStateOf("")
    private var homeJob: kotlinx.coroutines.Job? = null

    init {
        fetchHome()
    }

    fun selectFacet(f: String) {
        facet.value = if (facet.value == f) "" else f
        fetchHome()
    }

    private fun fetchHome() {
        homeJob?.cancel()
        val f = facet.value
        homeJob = viewModelScope.launch(Dispatchers.IO) {
            repository.provideHomeFeed(f).collect { feed ->
                _home.value = feed
            }
        }
    }
}
