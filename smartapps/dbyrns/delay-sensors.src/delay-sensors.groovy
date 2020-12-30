/**
 *  Delay Sensors
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
    name: "Delay Sensors",
    namespace: "dbyrns",
    author: "David Byrns",
    description: "Trigger virtual sensor based on master sensor after a period of time",
    category: "Convenience",
    iconUrl: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url: "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png")

preferences {
    section("Master motion sensor:") {
        input "mastersensor", "capability.contactSensor", required: true, title: "Where?"
    }
    section("Slave motion sensor:") {
        input "slavesensor", "capability.contactSensor", required: true, title: "Where?"
    }
    section("Delay before triggering slave (sec)") {
        input "triggerdelay", "number", required: true, title: "Secondes?"
    }
    section("Delay before turning off slave (Sec)") {
        input "turnoffdelay", "number", required: true, title: "Secondes?"
    }
}

def installed() {
	log.debug "Installed with settings: ${settings}"

	initialize()
}

def updated() {
	log.debug "Updated with settings: ${settings}"

	unsubscribe()
	initialize()
}

def initialize() {
    subscribe(mastersensor, "contact.open", contactOpenHandler)
    subscribe(mastersensor, "contact.closed", contactClosedHandler)
    if (mastersensor.currentState("contact").value == 'open'){
        slavesensor.open()
    } else {
        slavesensor.close()
    }
}

def contactOpenHandler(evt) {
	def timestamp = (now() % 3600000) / 1000
    log.debug "[$timestamp] contactOpenHandler called: $evt"
    if (triggerdelay > 0) {
    	def data = [delay: triggerdelay, action: 'open', timestamp: timestamp] 
    	runIn(triggerdelay, checkContact, [data: data])
    } else {
    	log.debug "Triggering slave contact without delay"
    	slavesensor.open()
    }
}

def contactClosedHandler(evt) {
	def timestamp = (now() % 3600000) / 1000
    log.debug "[$timestamp] contactStoppedHandler called: $evt"
    if (turnoffdelay > 0) {
    	def data = [delay: turnoffdelay, action: 'closed', timestamp: timestamp]
    	runIn(turnoffdelay, checkContact, [data: data])
    } else {
    	log.debug "Turning off slave contact without delay"
    	slavesensor.close()
    }
}

def checkContact(data) {
    log.debug "[${data.timestamp}] In checkContact scheduled method with ${data.delay} and ${data.action}"

    def contactState = mastersensor.currentState("contact")

    if (contactState.value == data.action) {
        // get the time elapsed between now and when the contact reported the last action
        def elapsed = now() - contactState.date.time

        // elapsed time is in milliseconds, so the threshold must be converted to milliseconds too
        def threshold = (1000 * data.delay) 

		//Remove 1sec of threshold to take into account runIn precision 
        if (elapsed >= (threshold - 1000)) {
            log.debug "Contact has stayed in ${data.action} long enough since last check ($elapsed ms): set the slave contact"
            if (data.action == 'open') {
            	slavesensor.open()
            } else {
            	slavesensor.close()
            }
        } else {
            log.debug "Contact has not stayed in ${data.action} long enough since last check ($elapsed ms):  not setting slave contact"
        }
    } else {
        // Contact has changed state; just log it and do nothing
        log.debug "Contact has changed state to ${contactState.value} and is no more in ${data.action}, do nothing and wait for the state to come back"
    }
}
