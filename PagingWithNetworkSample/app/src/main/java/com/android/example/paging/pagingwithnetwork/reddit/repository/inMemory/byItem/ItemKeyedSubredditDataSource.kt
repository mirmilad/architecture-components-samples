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

package com.android.example.paging.pagingwithnetwork.reddit.repository.inMemory.byItem

import androidx.lifecycle.MutableLiveData
import androidx.paging.CoroutineItemKeyedDataSource
import androidx.paging.ItemKeyedDataSource
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
 * A data source that uses the "name" field of posts as the key for next/prev pages.
 * <p>
 * Note that this is not the correct consumption of the Reddit API but rather shown here as an
 * alternative implementation which might be more suitable for your backend.
 * see PageKeyedSubredditDataSource for the other sample.
 */
class ItemKeyedSubredditDataSource(
        private val redditApi: RedditApi,
        private val subredditName: String)
    : CoroutineItemKeyedDataSource<String, RedditPost>() {
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
     * for it to return some success value before calling loadAfter and we don't support loadBefore
     * in this example.
     * <p>
     * See BoundaryCallback example for a more complete example on syncing multiple network states.
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

    override suspend fun loadBefore(params: LoadParams<String>): LoadResult<RedditPost> {
        return LoadResult.None
    }

    override suspend fun loadAfter(params: LoadParams<String>) : LoadResult<RedditPost> {
        do {
            try {
                // set network value to loading.
                networkState.postValue(NetworkState.LOADING)
                // even though we are using async retrofit API here, we could also use sync
                // it is just different to show that the callback can be called async.
                val response = redditApi.getTopAfter_suspend(subreddit = subredditName,
                        after = params.key,
                        limit = params.requestedLoadSize)

                val items = response?.data?.children?.map { it.data } ?: emptyList()
                // clear retry since last request succeeded
                retry = null
                networkState.postValue(NetworkState.LOADED)
                return LoadResult.Success(items)
            } catch (e: HttpException) {
                retry = {
                    retryImpl()
                }
                networkState.postValue(
                        NetworkState.error("error code: ${e.code()}"))
            } catch (e: Exception) {
                retry = {
                    retryImpl()
                }
                // publish the error
                networkState.postValue(NetworkState.error(e.message ?: "unknown err"))
            }
            retrySignal.receive()
        } while (true)
    }

    /**
     * The name field is a unique identifier for post items.
     * (no it is not the title of the post :) )
     * https://www.reddit.com/dev/api
     */
    override fun getKey(item: RedditPost): String = item.name

    override suspend fun loadInitial(params: LoadInitialParams<String>)
            : InitialResult<RedditPost> {
        do {
            try {
                // update network states.
                // we also provide an initial load state to the listeners so that the UI can know when the
                // very first list is loaded.
                networkState.postValue(NetworkState.LOADING)
                initialLoad.postValue(NetworkState.LOADING)

                val response = redditApi.getTop_suspend(
                        subreddit = subredditName,
                        limit = params.requestedLoadSize
                )

                val items = response?.data?.children?.map { it.data } ?: emptyList()
                retry = null
                networkState.postValue(NetworkState.LOADED)
                initialLoad.postValue(NetworkState.LOADED)
                return InitialResult.Success(items)

            }  catch (e: Exception) {
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