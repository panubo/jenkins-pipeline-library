/*
*    @Library("panubo") _
*    buildDockerImage {
*        gitCredentials = "panubot"
*        gitUrl = "https://github.com/panubo/docker-"
*        dockerName = "panubo/php-apache"
*        dockerRegistry = "docker.io"
*        version = "debian8"
*        subDirectory = "debian8"
*    }
*/

// TODO: Add image cache support

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    if (!config.subDirectory?.trim()) {
        config.subDirectory = '.'
    }

    def scmVars
    def dockerImage
    node {
        withDockerEnv {

            // Clean workspace
            // deleteDir()

            // stage('Checkout') {
            //     scmVars = checkout([$class: 'GitSCM',
            //         branches: [[name: 'refs/heads/master']],
            //         doGenerateSubmoduleConfigurations: false,
            //         userRemoteConfigs: [[
            //             credentialsId: "${config.gitCredentials}",
            //             url: "${config.gitUrl}"
            //         ]]
            //     ])
            //     env.GIT_COMMIT = scmVars.GIT_COMMIT
            //     env.GIT_BRANCH = scmVars.GIT_BRANCH
            //     env.GIT_PREVIOUS_COMMIT = scmVars.GIT_PREVIOUS_COMMIT
            //     env.GIT_PREVIOUS_SUCCESSFUL_COMMIT = scmVars.GIT_PREVIOUS_SUCCESSFUL_COMMIT
            //     env.GIT_URL = scmVars.GIT_URL
            //     sh 'printenv'
            // }

            stage('Build') {
                dir(config.subDirectory) {
                    dockerImage = docker.build("${config.dockerName}:${config.version}-${env.BUILD_ID}", '--pull .')
                }
            }

            stage('Push') {

                /* Authentication is done like this due to a bug in docker-workflow-plugin https://issues.jenkins-ci.org/browse/JENKINS-41051 */
                withCredentials([usernamePassword(credentialsId: 'dockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    sh('#!/bin/sh -e\necho "docker login -u $USERNAME -p ******** docker.io"\ndocker login -u $USERNAME -p $PASSWORD docker.io')
                }
                docker.withRegistry('https://docker.io') {
                    /* Push the container to the custom Registry */
                    dockerImage.push()
                }
            }
        }
    }
}
