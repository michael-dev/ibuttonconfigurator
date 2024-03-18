package com.mbr.ibuttonconfigurator.helper

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.MutableLiveData

open class MutableLiveDataWithLifecycle<T>: MutableLiveData<T>(), LifecycleOwner {
    private  var lifecycleRegistry = LifecycleRegistry(this)

    override fun onActive() {
        super.onActive()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    override fun onInactive() {
        super.onInactive()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    init {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
}