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

    def cacheRegistry             = config.containsKey('cacheRegistry') ? config.cacheRegistry : null
    def cacheRegistryHelper       = config.containsKey('cacheRegistryHelper') ? config.cacheRegistryHelper : "none"
    def cacheRegistryCredentialId = config.containsKey('cacheRegistryCredentialId') ? config.cacheRegistryCredentialId : null
    def cacheDefault              = config.containsKey('cacheDefault') ? config.cacheDefault : "master"

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
                dockerLogin(dockerRegistry: dockerRegistry, credentialId: credentialId, credentialHelper: credentialHelper)

                // If cacheRegistry do docker login
                if (cacheRegistry) {
                    def cacheDockerRegistry = cacheRegistry.split('/')[0]
                    // TODO: skip this if all the same as above
                    dockerLogin(dockerRegistry: cacheDockerRegistry, credentialId: cacheRegistryCredentialId, credentialHelper: cacheRegistryHelper)
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
                def imageDisplayName
                def cacheKeyPrefix
                if (distribution && workspace == ".") {
                    cacheKeyPrefix = sprintf( '%s-', [distribution])
                    imageDisplayName = distribution
                } else if (workspace != ".") {
                    cacheKeyPrefix = sprintf( '%s-', [workspace.replaceAll("/", "-")])
                    imageDisplayName = workspace.replaceAll("/", "-")
                } else {
                    cacheKeyPrefix = ""
                    imageDisplayName = "base"
                }


                def cacheKey
                def branch
                def cacheFrom = ""

                if (env.BRANCH_NAME) {
                    branch = env.BRANCH_NAME
                }
                else {
                    // This command will output "HEAD" if git is detached or a tag is checked out
                    branch = sh(returnStdout: true, script: "git rev-parse --symbolic-full-name --abbrev-ref HEAD").trim()
                }

                // Do some cache priming
                if (cacheRegistry) {
                    def cacheCommand

                    if (branch != "HEAD" && branch == cacheDefault) {
                        cacheKey = sprintf( '%s%s', [cacheKeyPrefix, branch.replaceAll("/", "-")])
                        cacheCommand = "docker pull ${cacheRegistry}:${cacheKey}"
                        cacheFrom = "--cache-from ${cacheRegistry}:${cacheKey}"
                    } else if (branch != "HEAD") {
                        cacheKey = sprintf( '%s%s', [cacheKeyPrefix, branch.replaceAll("/", "-")])
                        cacheKeyDefault = sprintf( '%s%s', [cacheKeyPrefix, cacheDefault.replaceAll("/", "-")])
                        cacheCommand = "docker pull ${cacheRegistry}:${cacheKey} || docker pull ${cacheRegistry}:${cacheKeyDefault}"
                        cacheFrom = "--cache-from ${cacheRegistry}:${cacheKey} --cache-from ${cacheRegistry}:${cacheKeyDefault}"
                    } else {
                        // if branch == "HEAD" we don't know what cache to pull in so use default only
                        cacheKeyDefault = sprintf( '%s%s', [cacheKeyPrefix, cacheDefault.replaceAll("/", "-")])
                        cacheCommand = "docker pull ${cacheRegistry}:${cacheKeyDefault}"
                        cacheFrom = "--cache-from ${cacheRegistry}:${cacheKeyDefault}"
                    }

                    stage("Prime Cache ${imageDisplayName}") {
                        // We don't want this to ever fail
                        sh(script: sprintf("%s || true", [cacheCommand]))
                    }
                }

                stage("Build ${imageDisplayName}") {
                    println "Building image: ${imageName} Workspace: ${workspace}"
                    dockerImage = docker.build("${imageName}:latest", sprintf("--pull %s %s", [cacheFrom, workspace])) // "--pull${cacheFrom} ${workspace}")
                    dockerImageInspect = readJSON text: sh(returnStdout: true, script: "docker image inspect --format '{{json . }}' ${imageName}:latest")
                }

                stage("Push ${imageDisplayName}") {
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

                    if (cacheKey) {
                        stage("Save Cache ${imageDisplayName}") {
                            sh(script: sprintf("docker tag %s %s:%s", [dockerImageInspect['Id'], cacheRegistry, cacheKey]))
                            sh(script: "docker push ${cacheRegistry}:${cacheKey}")
                        }
                    }
                }
            }
        }
    }
}
