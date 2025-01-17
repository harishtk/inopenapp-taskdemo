package com.example.taskdemo.feature.home.di

import com.example.taskdemo.core.di.RepositorySource
import com.example.taskdemo.core.di.RepositorySources
import com.example.taskdemo.feature.home.data.repository.DefaultInOpenAppRepository
import com.example.taskdemo.feature.home.data.repository.FakeOpenInAppRepository
import com.example.taskdemo.feature.home.data.source.inmemory.InMemoryInOpenAppDataSource
import com.example.taskdemo.feature.home.data.source.remote.InOpenAppRemoteDataSource
import com.example.taskdemo.feature.home.data.source.remote.NetworkInOpenAppRemoteDataSource
import com.example.taskdemo.feature.home.domain.repository.InOpenAppRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface InOpenAppRepositoryModule {

    @Binds
    @RepositorySource(RepositorySources.Default)
    fun bindsDefaultInOpenAppRepository(
        inOpenAppRepository: DefaultInOpenAppRepository
    ): InOpenAppRepository

    @Binds
    @RepositorySource(RepositorySources.Fake)
    fun bindsFakeInOpenAppRepository(
        inOpenAppRepository: FakeOpenInAppRepository
    ): InOpenAppRepository

    @Binds
    fun bindsInOpenAppRemoteDataSource(
        inOpenAppRemoteDataSource: NetworkInOpenAppRemoteDataSource
    ): InOpenAppRemoteDataSource

    companion object {
        @Provides
        @Singleton
        fun providesInMemoryDataSource(): InMemoryInOpenAppDataSource =
            InMemoryInOpenAppDataSource()
    }

}