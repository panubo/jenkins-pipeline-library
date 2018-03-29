def call(Map params = [:]) {
    def dockerRegistry   = params.containsKey('dockerRegistry')   ? params.dockerRegistry   : "docker.io"
    def credentialId     = params.containsKey('credentialId')     ? params.credentialId     : null
    def credentialHelper = params.containsKey('credentialHelper') ? params.credentialHelper : "none"

    switch (credentialHelper) {
        case "none":
            /* Authentication is done like this due to a bug in docker-workflow-plugin https://issues.jenkins-ci.org/browse/JENKINS-41051 */
            if (credentialId) {
                println "Authentication basic docker login"
                withCredentials([usernamePassword(credentialsId: credentialId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    sh('#!/bin/sh -e\necho "docker login -u $USERNAME -p ******** ' + dockerRegistry + '"\ndocker login -u $USERNAME -p $PASSWORD ' + dockerRegistry)
                }                                
            }
            break
        case "ecr":
            println "Authentication aws ecr login"
            def awsRegion = dockerRegistry.split('\\.')[-3]
            withAWS(region: awsRegion, credentials: credentialId) {
                def login = ecrLogin()
                sh('#!/bin/sh -e\necho "docker login ********"\n' + login)
            }
            break
        case "gcr":
            println "Authentication gcloud login"
            if (credentialId) {
                withCredentials([file(credentialsId: credentialId, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]) {
                    println "GCR login with credentials file"
                    def gcrCredentials = readJSON text: sh(returnStdout: true, script: "echo https://${dockerRegistry} | docker-credential-gcr get")
                    sh('#!/bin/sh -e\necho "docker login -u '+ gcrCredentials['Username'] +' -p ******** https://' + dockerRegistry + '"\ndocker login -u '+ gcrCredentials['Username'] +' -p '+ gcrCredentials['Secret'] +' https://' + dockerRegistry)
                }
            } else {
                println "GCR login from metadata"
                def gcrCredentials = readJSON text: sh(returnStdout: true, script: "echo https://${dockerRegistry} | docker-credential-gcr get")
                sh('#!/bin/sh -e\necho "docker login -u '+ gcrCredentials['Username'] +' -p ******** https://' + dockerRegistry + '"\ndocker login -u '+ gcrCredentials['Username'] +' -p '+ gcrCredentials['Secret'] +' https://' + dockerRegistry)
            }
            break
    }

}
