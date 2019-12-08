/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.example.paging.pagingwithnetwork.reddit.repository.inMemory.byPage

import androidx.lifecycle.MutableLiveData
import androidx.paging.CoroutinePageKeyedDataSource
import androidx.paging.PageKeyedDataSource
import com.android.example.paging.pagingwithnetwork.reddit.api.RedditApi
import com.android.example.paging.pagingwithnetwork.reddit.repository.NetworkState
import com.android.example.paging.pagingwithnetwork.reddit.vo.RedditPost
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import retrofit2.Call
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.lang.Exception
import java.util.concurrent.Executor

/**
 * A data source that uses the before/after keys returned in page requests.
 * <p>
 * See ItemKeyedSubredditDataSource
 */
class PageKeyedSubredditDataSource(
        private val redditApi: RedditApi,
        private val subredditName: String) : CoroutinePageKeyedDataSource<String, RedditPost>() {

    // keep a function reference for the retry event
    private var retry: (() -> Any)? = null

    private val retrySignal = Channel<Boolean>()
    private fun retryImpl() {
        coroutineScope.launch {
            retrySignal.send(true)
        }
    }
    /**
     * There is no sync on the state because paging will always call loadInitial first then wait
     * for it to return some success value before calling loadAfter.
     */
    val networkState = MutableLiveData<NetworkState>()

    val initialLoad = MutableLiveData<NetworkState>()

    fun retryAllFailed() {
        val prevRetry = retry
        retry = null
        prevRetry?.let {
            it.invoke()
        }
    }

    override suspend fun loadAfter(params: LoadParams<String>): LoadResult<String, RedditPost> {
        do {
            try {
                networkState.postValue(NetworkState.LOADING)
                val response = redditApi.getTopAfter_suspend(subredditName, after = params.key, limit = params.requestedLoadSize)
                val data = response?.data
                val items = data?.children?.map { it.data } ?: emptyList()
                retry = null
                networkState.postValue(NetworkState.LOADED)
                return LoadResult.Success(items, data?.after)
            } catch (e: HttpException) {
                retry = {
                    retryImpl()
                }
                networkState.postValue(NetworkState.error("error code: ${e.code()}"))
            }
            catch (e: Exception) {
                retry = {
                    retryImpl()
                }
                networkState.postValue(NetworkState.error(e.message ?: "unknown err"))
            }
            retrySignal.receive()
        } while (true)
    }

    override suspend fun loadBefore(params: LoadParams<String>): LoadResult<String, RedditPost> {
        return LoadResult.None
    }

    override suspend fun loadInitial(params: LoadInitialParams<String>): InitialResult<String, RedditPost> {
        do {
            networkState.postValue(NetworkState.LOADING)
            initialLoad.postValue(NetworkState.LOADING)
            // triggered by a refresh, we better execute sync
            try {
                val response = redditApi.getTop_suspend(
                        subreddit = subredditName,
                        limit = params.requestedLoadSize
                )
                val data = response?.data
                val items = data?.children?.map { it.data } ?: emptyList()
                retry = null
                networkState.postValue(NetworkState.LOADED)
                initialLoad.postValue(NetworkState.LOADED)
                return InitialResult.Success(items, data?.before, data?.after)
            } catch (e: Exception) {
                retry = {
                    retryImpl()
                }
                val error = NetworkState.error(e.message ?: "unknown error")
                networkState.postValue(error)
                initialLoad.postValue(error)
            }
            retrySignal.receive()
        } while (true)
    }
}