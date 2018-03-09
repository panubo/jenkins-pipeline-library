/**
 * @Library("panubo") _
 * buildDockerImage{
 *     repo = "https://github.com/panubo/docker-*.git"
 *     dockerName = "panubo/php-apache"
 *     dockerRegistry = "docker.io" # Exclude the https:// prefix
 *     version = "debian8"
 *     subDirectory = "debian8"
 * }
 */

// TODO: Add image cache support

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def repo = config.containsKey('repo') ? config.repo : null
    def dockerName = config.containsKey('dockerName') ? config.dockerName : null
    def dockerRegistry = config.containsKey('dockerRegistry') ? config.dockerRegistry : "docker.io"
    def version = config.containsKey('version') ? config.version : env.BRANCH_NAME
    def subDirectory = config.containsKey('subDirectory') ? config.subDirectory : '.'
    boolean dryRun = config.containsKey('dryRun') ? config.dryRun : false
    
    if (version == null) {
        error 'buildDockerImages must be used as part of a Multibranch Pipeline *or* a `version` argument must be provided'
    }

    def scmVars
    def dockerImage
    node {
        withDockerEnv {

            // Clean workspace
            deleteDir()

            stage("Checkout") {
                if (env.BRANCH_NAME) {
                    checkout scm
                }
                else if ((env.BRANCH_NAME == null) && (repo)) {
                    git repo
                }
                else {
                    error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
                }
            }

            stage('Build') {
                // This might be broken by https://issues.jenkins-ci.org/browse/JENKINS-33510
                dir(subDirectory) {
                    dockerImage = docker.build("${dockerName}:${version}-${env.BUILD_ID}", '--pull .')
                }
            }

            if (!dryRun) {
            stage('Push') {
                /* Authentication is done like this due to a bug in docker-workflow-plugin https://issues.jenkins-ci.org/browse/JENKINS-41051 */
                withCredentials([usernamePassword(credentialsId: 'dockerHub', usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                    sh('#!/bin/sh -e\necho "docker login -u $USERNAME -p ******** ' + dockerRegistry + '"\ndocker login -u $USERNAME -p $PASSWORD ' + dockerRegistry)
                }
                docker.withRegistry('https://' + dockerRegistry) {
                    /* Push the container to the custom Registry */
                    dockerImage.push()
                }
            }
            }
        }
    }
}
