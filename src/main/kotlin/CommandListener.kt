package com.github.rei0925.mfweb

import com.github.rei0925.kotlincli.commands.CommandAlias
import com.github.rei0925.kotlincli.commands.Subcommand

@CommandAlias("mfweb")
class CommandListener() {
    @Subcommand("start")
    fun start(){
        RealTimeCast.start()
    }
    @Subcommand("stop")
    fun stop(){
        RealTimeCast.stopInstance()
    }
}