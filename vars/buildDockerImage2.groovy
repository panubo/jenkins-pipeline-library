/**
 * State: BETA
 *
 * @Library("panubo") _
 * buildDockerImage2{
 *     repo = "null" # Set if not using a Multibranch Pipeline
 *     tagPolicy = "branchBuild"
 *     tagLatest = false
 *     distribution = null
 *     credentialHelper = "none"
 *     credentialId = null # Set the credentials for the helper. Credentials should be u/p for ecr or file for gcr
 *     artifacts = [ # Multiple related artifacts can be built, all will received the same tag
 *       [imageName: "", workspace: "."]
 *     ]
 *     dryRun = false
 * }
 */

// TODO: Add image cache support
// TODO: Add alternate Dockerfile name support

def call(body) {

    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def repo       = config.containsKey('repo') ? config.repo : null
    boolean dryRun = config.containsKey('dryRun') ? config.dryRun : false

    def artifacts    = config.containsKey('artifacts') ? config.artifacts : []
    def tagPolicy    = config.containsKey('tagPolicy') ? config.tagPolicy : "branchBuild"
    // def tagLatest    = config.containsKey('tagLatest') ? config.tagLatest : false
    def distribution = config.containsKey('distribution') ? config.distribution : null

    def credentialHelper = config.containsKey('credentialHelper') ? config.credentialHelper : "none"
    def credentialId     = config.containsKey('credentialId') ? config.credentialId : null

    def scmVars
    node {
        withDockerEnv {

            stage("Setup") {
                if (env.BRANCH_NAME) {
                    checkout scm
                }
                else if ((env.BRANCH_NAME == null) && (repo)) {
                    git repo
                }
                else {
                    error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
                }

                // Do credential helper here - login may be required for build
                def dockerRegistry = artifacts[0].imageName.split('/')[0]
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
                                // Don't know if this is crazy or not
                                def gcrCredentials = readJSON text: sh(returnStdout: true, script: "echo https://${dockerRegistry} | docker-credential-gcr get")
                                sh('#!/bin/sh -e\necho "docker login -u '+ gcrCredentials['Username'] +' -p ******** https://' + dockerRegistry + '"\ndocker login -u '+ gcrCredentials['Username'] +' -p '+ gcrCredentials['Secret'] +' https://' + dockerRegistry)
                            }
                        } else {
                            println "GCR login from metadata"
                            // Don't know if this is crazy or not
                            def gcrCredentials = readJSON text: sh(returnStdout: true, script: "echo https://${dockerRegistry} | docker-credential-gcr get")
                            sh('#!/bin/sh -e\necho "docker login -u '+ gcrCredentials['Username'] +' -p ******** https://' + dockerRegistry + '"\ndocker login -u '+ gcrCredentials['Username'] +' -p '+ gcrCredentials['Secret'] +' https://' + dockerRegistry)
                        }
                        break
                }
            }

            artifacts.each {
                def dockerImage
                def dockerImageInspect
                def dockerTags = []
                // if (tagLatest) {
                //     dockerTags.add("latest")
                // }
                def imageName = it['imageName']
                def workspace = it.containsKey('workspace') ? it["workspace"].replaceAll('/+$', '') : "."

                stage("Build ${imageName}") {
                    println "Building image: ${imageName} Workspace: ${workspace}"
                    dockerImage = docker.build("${imageName}:latest", "--pull ${workspace}")
                    dockerImageInspect = readJSON text: sh(returnStdout: true, script: "docker image inspect --format '{{json . }}' ${imageName}:latest")
                }

                stage("Push ${imageName}") {
                    switch (tagPolicy) {
                        case "branchBuild":
                            println "tagging with branchBuild"
                            // dockerTag() does BRANCH_NAME-BUILD_NUMBER with some replace
                            dockerTags.add(dockerTag())
                            break
                        case "sha256":
                            println "tagging with sha256"
                            def dockerImageId = dockerImageInspect['Id'].split(':')[1]
                            dockerTags.add(dockerImageId)
                            break
                        case "gitCommit":
                            println "tagging with gitCommit"
                            def gitCommit = sh(returnStdout: true, script: "git rev-parse --short HEAD").trim()
                            dockerTags.add(gitCommit)
                            break
                        case "version":
                            println "tagging with version"
                            def gitDescribe = sh(returnStdout: true, script: "git describe --abbrev=1 --tags --always").trim()
                            def versionParse = (gitDescribe =~ /^(\d+)\.(\d+)\.(\d+)(\-(\d+))?$/)
                            if (versionParse.size() == 1) {
                                dockerTags.add(sprintf( '%s.%s.%s', [versionParse[0][1], versionParse[0][2], versionParse[0][3]]))
                                dockerTags.add(sprintf( '%s.%s', [versionParse[0][1], versionParse[0][2]]))
                                dockerTags.add(sprintf( '%s', [versionParse[0][1]]))
                                if (versionParse[0][5] != null) {
                                    dockerTags.add(sprintf( '%s.%s.%s-%s', [versionParse[0][1], versionParse[0][2], versionParse[0][3], versionParse[0][5]]))
                                }
                            } else {
                                dockerTags.add(gitDescribe)
                            }
                            break
                        case "date":
                            println "tagging with date"
                            date = new Date().format('yyyyMMdd')
                            if (distribution && workspace == ".") {
                                dockerTags.add(sprintf( '%s-%s', [distribution, date]))
                                dockerTags.add(distribution)
                            } else if (workspace != ".") {
                                workspace_distribution = workspace.replaceAll("/", "-")
                                dockerTags.add(sprintf( '%s-%s', [workspace_distribution, date]))
                                dockerTags.add(workspace_distribution)
                            } else {
                                dockerTags.add(date)
                            }
                            break
                    }
                    // Do docker push
                    dockerTags.each {
                        if (dryRun) {
                            println "Tagging and pushing ${imageName}:${it}"
                        } else {
                            dockerImage.push(it)
                        }
                    }
                }
            }
        }
    }
}
