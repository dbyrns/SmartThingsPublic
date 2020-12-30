/**
 *  Eco Mode
 *
 *  Copyright 2020 David Byrns
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
definition(
        name: "Eco Mode",
        namespace: "dbyrns",
        author: "David Byrns",
        description: "Permet de mettre en veille le thermostat sous certaines conditions",
        category: "Convenience",
        iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
        iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
        iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")


preferences {
    section("Thermostat") {
        input "thermostat", "capability.thermostatCoolingSetpoint", required: true, title: "Device"
    }
    section("Outdoor temperature sensor") {
        input "outTempSensor", "capability.temperatureMeasurement", required: false, title: "Device"
    }
    section("Eco settings (away or door open)") {
        input "sensor", "capability.contactSensor", required: true, title: "Sensor"
        input "ecoMinTemp", "number", required: true, title: "Heat threshold (min)"//TODO Could use decimal type but enforce .0 or .5
        input "ecoMaxTemp", "number", required: true, title: "Cool threshold (max)"
    }
    section("Night settings") {
        input "nightMinTemp", "number", required: true, title: "Heat"
        input "nightMaxTemp", "number", required: true, title: "Cool"
    }
    section("Home settings") {
        input "homeMinTemp", "number", required: true, title: "Heat"
        input "homeMaxTemp", "number", required: true, title: "Cool"
    }
    section("Wakeup settings") {
        input "wakeupStartTime", "time", required: true, title: "Wakeup Start Time "
        input "wakeupEndTime", "time", required: true, title: "Wakeup End Time"
        input "wakeupMinTemp", "number", required: true, title: "Heat"
        input "wakeupMaxTemp", "number", required: true, title: "Cool"
    }
    section("Send Push Notification?") {
        input "sendPush", "bool", required: false,
                title: "Send Push Notification when Opened?"
    }
}

def installed() {
    log.debug "Installed with settings: ${settings}"

    initialize()
}

def updated() {
    log.debug "Updated with settings: ${settings}"

    unsubscribe()
    unschedule()
    initialize()
}

def initialize() {
    state.subscribed = false
    state.locationMode = null
    state.cool = null
    state.heat = null
    cacheThermostatMode()
    subscribe(location, "mode", modeChangeHandler)
    subscribe(sensor, "contact", contactHandler)
    schedule(wakeupStartTime, wakeupStartHandler)
    schedule(wakeupEndTime, wakeupEndHandler)
    subscribeToThermostatEvents()
    updateCurrentMode([origin: "Settings changed",
                       sendNotification: true])
}

def subscribeToThermostatEvents() {
    if (!state.subscribed) {
        subscribe(thermostat, "temperature", termTempHandler)
        subscribe(thermostat, "thermostatMode", termHandler)
        subscribe(thermostat, "coolingSetpoint", termHandler)
        subscribe(thermostat, "heatingSetpoint", termHandler)
        state.subscribed = true
    }
}

def unsubscribeFromThermostatEvents() {
    if (state.subscribed) {
        unsubscribe(thermostat)
        state.subscribed = false
    }
}

def contactHandler(evt) {
    updateCurrentMode([origin: "Contact $evt.value"])
}

def modeChangeHandler(evt) {
    updateCurrentMode([origin: "Location mode changed to $evt.value"])
}

def termTempHandler(evt) {
    updateCurrentMode([origin: "Temperature changed to $evt.value",
                       sendNotification: false])
}

def termHandler(evt) {
    cacheThermostatMode()
    if (evt.name == "coolingSetpoint") {
        log.debug("Caching temporary cooling setpoint of $evt.value")
        state.cool = evt.value
    } else if (evt.name == "heatingSetpoint") {
        log.debug("Caching temporary heating setpoint of $evt.value")
        state.heat = evt.value
    }
    updateCurrentMode([origin: "Thermostat $evt.name changed to $evt.value"])
}

def wakeupStartHandler(evt) {
    updateCurrentMode([origin: "Wakeup start time"])
}

def wakeupEndHandler(evt) {
    updateCurrentMode([origin: "Wakeup end time"])
}

def cacheLocationMode(locationMode) {
    if (state.locationMode != locationMode) {
        state.locationMode = locationMode
        state.cool = null
        state.heat = null
    }
}

def cacheThermostatMode() {
    def mode = thermostat.currentState("thermostatMode").value
    if (mode != "off") {
        state.lastThermMode = mode
        log.debug "Saving current thermostat mode : $state.lastThermMode"
    } else {
        log.debug "Not updating cached thermostat mode ($state.lastThermMode) because it's off now"
    }
}

def toDayMinute(time) {
    //Put values in minute ( /60000) in day range ( %1440)
    return (long)(time / 60000) % 1440
}

def getCondition(settings, system) {
    def from = toDayMinute(new Date().parse("yyy-MM-dd'T'HH:mm:ss.SSSZ", settings.wakeupStartTime).getTime())
    def to = toDayMinute(new Date().parse("yyy-MM-dd'T'HH:mm:ss.SSSZ", settings.wakeupEndTime).getTime())
    def now = toDayMinute(system.time)

    if (system.currentMode == 'Away') {
        return "Away"
    }
    //To be sure that when called the period is already began or finished and not about to be, remove 5 minutes to each boundary
    else if (now > (from - 5) && now < (to - 5)) {
        return "Wakeup"
    } else if (system.currentMode == 'Night') {
        return "Night"
    } else { //system.currentMode == 'Home'
        return "Home"
    }
}

def getAllCondition(settings, system) {
    def allCondition = [base: getCondition(settings, system)]

    if (system.sensorState == 'open' || allCondition.base == "Away") {
        if (system.sensorState == 'open') {

            if (system.outTemp == null ||
                    (system.lastThermMode == "cool" && system.outTemp > system.inTemp) ||
                    (system.lastThermMode == "heat" && system.outTemp < system.inTemp)) {
                allCondition["sensor"] = [state: "OpenWaste",
                                          inTemp: system.inTemp,
                                          outTemp: system.outTemp]
            } else {
                allCondition["sensor"] = [state: "OpenOk",
                                          inTemp: system.inTemp,
                                          outTemp: system.outTemp]
            }
        }
        if (system.lastThermMode == 'heat' && system.inTemp < settings.ecoMinTemp) {
            allCondition["override"] = [state: "TooLow",
                                        inTemp: system.inTemp]
        } else if (system.lastThermMode == 'cool' && system.inTemp > settings.ecoMaxTemp) {
            allCondition["override"] = [state: "TooHigh",
                                        inTemp: system.inTemp]
        }
    }
    return allCondition
}

def getSettings() {
    def settings = [
            wakeupStartTime: wakeupStartTime,
            wakeupEndTime: wakeupEndTime,
            ecoMinTemp: (double)ecoMinTemp,
            ecoMaxTemp: (double)ecoMaxTemp,
            wakeupMinTemp: (double)wakeupMinTemp,
            wakeupMaxTemp: (double)wakeupMaxTemp,
            nightMinTemp: (double)nightMinTemp,
            nightMaxTemp: (double)nightMaxTemp,
            homeMinTemp: (double)homeMinTemp,
            homeMaxTemp: (double)homeMaxTemp,
    ]
    return settings
}

def getSystemState() {
    def system = [
            lastThermMode: state.lastThermMode,
            currentMode: location.currentMode,
            cachedLocationMode: state.locationMode,
            cachedCool: state.cool,
            cachedHeat: state.heat,
            sensorState: sensor.currentState("contact").value,
            inTemp: (double)Float.valueOf(thermostat.currentState("temperature").value),
            outTemp: null,
            time: now()
    ]
    if (outTempSensor) {
        system.outTemp = (double)Float.valueOf(outTempSensor.currentState("temperature").value)
    }
    return system
}

def getCommand() {
    def command = [cool: "coolingSetpoint",
                   heat: "heatingSetpoint",
                   mode: "thermostatMode"]
    return command
}

def getCool(locationMode, system, cool) {
    if (locationMode == system.cachedLocationMode && system.cachedCool) {
        return system.cachedCool
    }
    return cool
}

def getHeat(locationMode, system, heat) {
    if (locationMode == system.cachedLocationMode && system.cachedHeat) {
        return system.cachedHeat
    }
    return heat
}

def getOperatingValues(settings, system) {
    def allCondition = getAllCondition(settings, system)
    def data = [:]

    if (allCondition.base == "Wakeup") {
        data = [cool: getCool(allCondition.base, system, settings.wakeupMaxTemp),
                heat: getHeat(allCondition.base, system, settings.wakeupMinTemp),
                mode: system.lastThermMode,
                condition: "in wakeup period"
        ]
    } else if (allCondition.base == "Away") {
        data = [mode: "off",
                condition: "location in away mode"
        ]
    } else if (allCondition.base == "Night") {
        data = [cool: getCool(allCondition.base, system, settings.nightMaxTemp),
                heat: getHeat(allCondition.base, system, settings.nightMinTemp),
                mode: system.lastThermMode,
                condition: "location in night mode"
        ]
    } else if (allCondition.base == "Home") {
        data = [cool: getCool(allCondition.base, system, settings.homeMaxTemp),
                heat: getHeat(allCondition.base, system, settings.homeMinTemp),
                mode: system.lastThermMode,
                condition: "location in home mode"
        ]
    }

    if (data.mode != "off" && allCondition.containsKey("sensor")) {
        if (allCondition.sensor.state == "OpenOk") {
            data.condition = data.condition + " and sensor is open but not wasting energy (in: $allCondition.sensor.inTemp, out: $allCondition.sensor.outTemp)"
        } else if (allCondition.sensor.state == "OpenWaste") {
            data = [mode: "off",
                    condition: "sensor is open and wasting energy (in: $allCondition.sensor.inTemp, out: $allCondition.sensor.outTemp)"
            ]
        }
    }

    if (data.mode == "off" && allCondition.containsKey("override")) {
        if (allCondition.override.state == "TooLow") {
            data = [heat: settings.ecoMinTemp,
                    mode: system.lastThermMode,
                    condition: data.condition + " but inside temp. is too low ($allCondition.override.inTemp < $settings.ecoMinTemp)"
            ]
        } else if (allCondition.override.state == "TooHigh") {
            data = [cool: settings.ecoMaxTemp,
                    mode: system.lastThermMode,
                    condition: data.condition + " but inside temp is too high ($allCondition.override.inTemp > $settings.ecoMaxTemp)"
            ]
        }
    }
    data.locationMode= allCondition.base
    return data
}

def isOk(String target, String current) {
    return current == target
}

def isOk(double target, String current) {
    return Math.abs((double)Float.valueOf(current) - target) < 0.25
}

def _setState(data) {
    if (data.state == "coolingSetpoint") {
        thermostat.setCoolingSetpoint(data.value)
    } else if (data.state == "heatingSetpoint") {
        thermostat.setHeatingSetpoint(data.value)
    } else if (data.state == "thermostatMode") {
        if (data.value == 'off') {
            cacheThermostatMode()
        }
        thermostat.setThermostatMode(data.value)
    }
}

def updateCurrentMode(context) {
    def data = getOperatingValues(getSettings(), getSystemState())
    def command = getCommand()
    def getCurrentState = {state -> return (String)thermostat.currentState(state).value}
    def delayRunner = {delay, _data ->
        //Will avoid our own action to trigger an event
        unsubscribeFromThermostatEvents()
        if (delay > 0) {
            runIn(delay, _setState, [overwrite: false, data: _data])
        } else {
            _setState(_data)
        }
    }
    cacheLocationMode(data.locationMode)
    def status = _updateCurrentMode(context.origin, data, command, getCurrentState, delayRunner)

    context.checkStatus = ""
    if (status.cmd_sent) {
        //If a command is sent, force the notification
        context.sendNotification = true
        if (context.containsKey("checkCount")) {
            context.checkStatus = " (failed x$context.checkCount)"
        } else {
            context.checkCount = 0
        }
        //If a command is sent, check also the status in 2 minutes...
        if (context.checkCount < 3) {
            runIn(120, updateCurrentMode, [data: [origin: "reliability check",
                                                  checkCount: context.checkCount + 1,
                                                  sendNotification: true]])
        }
    } else {
        if (context.containsKey("checkCount")) {
            context.checkStatus = " (ok)"
            //If its the first check that is ok, no need to warn anybody about it
            if (context.checkCount == 1) {
                context.sendNotification = false
            }
        }
        //No command sent, we can safely subscribe again to thermostat events
        subscribeToThermostatEvents()
    }
    log.debug "$context.origin$context.checkStatus : $status.condition$status.action"
    if (sendPush && context.get("sendNotification", false)) {
        sendPush("$context.origin$context.checkStatus : $status.condition$status.action")
    }
}

def _updateCurrentMode(reason, data, command, getCurrentState, delayRunner) {
    def actions = []
    def noops = []
    def delay = 0

    command.each {
        if (data.containsKey(it.key)) {
            def target = data[it.key]
            def current = getCurrentState(it.value)
            if (isOk(target, current)) {
                noops.add("$it.key= $target")
            } else {
                actions.add("$it.key: $current->$target")
                delayRunner(delay, [state: it.value, value: target])
                delay = delay + 30
            }
        }
    }
    def action = ""
    if (actions) {
        action = ", update (" + actions.join(", ") + ")"
    }
    if (noops) {
        action = action + ", ok (" + noops.join(", ") + ")"
    }
    Boolean cmd_sent = delay > 0
    return [msg: "$reason : $data.condition$action",
            condition: data.condition,
            action: action,
            cmd_sent: cmd_sent]
}

//Comment below for deployment
/*
def testSuite() {
    testCmd()
    testAllOperatingValues()
}

def testPrint(msg) {
    println(msg)
}

def getTestSettings() {
    def settings = [
            wakeupStartTime: "2020-01-01T06:30:00.000-0400",
            wakeupEndTime  : "2020-01-01T09:00:00.000-0400",
            ecoMinTemp     : (double) 17.0,
            ecoMaxTemp     : (double) 29.0,
            wakeupMinTemp  : (double) 22.0,
            wakeupMaxTemp  : (double) 28.0,
            nightMinTemp   : (double) 19.0,
            nightMaxTemp   : (double) 23.0,
            homeMinTemp    : (double) 21.0,
            homeMaxTemp    : (double) 25.0,
    ]
    return settings
}

def getTestSystemState() {
    def system = [
            lastThermMode: 'cool',
            currentMode: 'Home',
            cachedLocationMode: null,
            cachedCool: null,
            cachedHeat: null,
            sensorState: 'closed',
            inTemp: '24',
            outTemp: '23',
            time: new Date(2020, 1, 1, 17, 0, 0).getTime()
    ]
    return system
}

def testCmd() {
    def data = getOperatingValues(getTestSettings(), getTestSystemState())
    def command = getCommand()
    def getCurrentState = {state ->
        if (state == 'mode') {
            return 'cool'
        } else {
            return '23.5'
        }
    }
    def delayRunner = {delay, _data ->
        testPrint("Sleeping $delay")
        testPrint("Settings $_data.state to $_data.value")
    }
    def status = _updateCurrentMode("test", data, command, getCurrentState, delayRunner)
    testPrint(status.msg)
    assert(status.cmd_sent)
    assert(status.msg.indexOf("cool:") > 0)
    assert(status.msg.indexOf("heat:") > 0)
    assert(status.msg.indexOf("mode:") > 0)
}

def testAllOperatingValues() {
    settings = getTestSettings()

    def mode = ['Home', 'Night', 'Away']
    def therMode = ['cool', 'heat']
    def sensor = ['open', 'closed']
    def time = [new Date(2020, 1, 1, 7, 45, 0).getTime(),
                new Date(2020, 1, 1, 11, 0, 0).getTime()]
    def inTemp = [16.0, 20.0, 25.0, 30.0]
    def outTemp = [null, 22.0]
    def locationMode = ['Wakeup', 'Home', 'Night', null]
    def cachedTemp = [40.0, null]
    def nb_test = mode.size() * therMode.size() * sensor.size() * time.size() * inTemp.size() * outTemp.size() * locationMode.size() * cachedTemp.size()
    def current_test = 1
    mode.each { it_mode ->
        therMode.each { it_therMode->
            sensor.each { it_sensor->
                time.each { it_time->
                    inTemp.each { it_inTemp->
                        outTemp.each { it_outTemp->
                            locationMode.each { it_locationMode->
                                cachedTemp.each { it_cachedTemp->
                                    def system = [
                                            lastThermMode: it_therMode,
                                            currentMode: it_mode,
                                            cachedLocationMode: it_locationMode,
                                            cachedCool: it_cachedTemp,
                                            cachedHeat: it_cachedTemp,
                                            sensorState: it_sensor,
                                            inTemp: it_inTemp,
                                            outTemp: it_outTemp,
                                            time: it_time
                                    ]
                                    testOperatingValues(settings, system, current_test, nb_test)
                                    current_test++
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

def testOperatingValues(settings, system, current_test, nb_test) {
    def data = getOperatingValues(settings, system)

    testPrint("[$current_test/$nb_test] evaluating output : $data\n  with system state : $system ")
    if (data.mode == 'off') {
        assert(system.currentMode == 'Away' || system.sensorState == 'open')
        assert(system.lastThermMode == 'cool' || system.inTemp > settings.ecoMinTemp)
        assert(system.lastThermMode == 'heat' || system.inTemp < settings.ecoMaxTemp)
        //If door is open, off should occur only of wasting is occuring
        //Wasting occur while heating if in temp is greater than out
        assert(system.currentMode == 'Away' || system.outTemp == null || system.lastThermMode == 'cool' || system.inTemp > system.outTemp)
        assert(system.currentMode == 'Away' || system.outTemp == null || system.lastThermMode == 'heat' || system.inTemp < system.outTemp)
    } else {
        def from = toDayMinute(new Date().parse("yyy-MM-dd'T'HH:mm:ss.SSSZ", settings.wakeupStartTime).getTime())
        def to = toDayMinute(new Date().parse("yyy-MM-dd'T'HH:mm:ss.SSSZ", settings.wakeupEndTime).getTime())
        def sensorOpen = (system.sensorState == 'open')
        def tempDeltaOk = system.outTemp != null &&
                          (data.mode == 'cool' && system.inTemp > system.outTemp) || //check for null
                          (data.mode == 'heat' && system.inTemp < system.outTemp)
        def ecoOverride =  (data.mode == 'cool' && system.inTemp > settings.ecoMaxTemp) ||
                           (data.mode == 'heat' && system.inTemp < settings.ecoMinTemp)

        //Check genuine conditions for running
        assert(!sensorOpen || ecoOverride || tempDeltaOk)
        assert(system.currentMode == 'Home' || system.currentMode == 'Night' || ecoOverride)

        def curTime = toDayMinute(system.time)
        //This block check conditions under which eco override temp is accepted
        if (ecoOverride && ((sensorOpen && !tempDeltaOk) || system.currentMode == 'Away')) {
            assert (data.mode == 'heat' || data.cool == settings.ecoMaxTemp)
            assert (data.mode == 'cool' || data.heat == settings.ecoMinTemp)
        } else if (curTime > from && curTime < to) {
            if (system.cachedCool &&
                system.cachedHeat &&
                system.cachedLocationMode == data.locationMode) {
                assert(data.mode == 'cool' || data.heat == system.cachedHeat)
                assert(data.mode == 'heat' || data.cool == system.cachedCool)
            } else {
                assert(data.mode == 'cool' || data.heat == settings.wakeupMinTemp)
                assert(data.mode == 'heat' || data.cool == settings.wakeupMaxTemp)
            }
        }
        //This block check conditions under which normal temp is respected
        else if (!sensorOpen || tempDeltaOk) {
            if (system.cachedCool &&
                system.cachedHeat &&
                system.cachedLocationMode == data.locationMode) {
                assert(data.heat == system.cachedHeat)
                assert(data.cool == system.cachedCool)
            } else {
                assert (system.currentMode == 'Night' || data.cool == settings.homeMaxTemp)
                assert (system.currentMode == 'Night' || data.heat == settings.homeMinTemp)
                assert (system.currentMode == 'Home' || data.cool == settings.nightMaxTemp)
                assert (system.currentMode == 'Home' || data.heat == settings.nightMinTemp)
            }
        } else {
            //Should not happens
            assert(false)
        }
        //This block check that the mode is correctly set
        assert (data.mode == 'heat' || system.lastThermMode == 'cool')
        assert (data.mode == 'cool' || system.lastThermMode == 'heat')
    }
}

//Main to run test suite
def preferences(values) {}
def definition(values) {}
testSuite();
*/