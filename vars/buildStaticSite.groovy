/**
 * State: BETA
 *
 * @Library("panubo") _
 * buildStaticSite {
 *     repo = "null" # Set if not using a Multibranch Pipeline
 *     buildImage = "docker.io/panubo/hugo:latest" # Set to null to skip building
 *     commands = [
 *       "hugo -d public"
 *       "hugo -D -d draft"
 *     ]
 *     artifacts = [ # Multiple artifacts can be built and deploy, all will receive the same tag
 *       [file: "public", deploy: null]
 *     ]
 *     publishBucket = null # Bucket to public artifacts to. Supports s3:// and gs://
 *     dryRun = false
 * }
 */

/**
 * This pipeline uses a Docker image to build a static site or static assets.
 * Artifacts can be saved in an archive on object storage and/or deployed directly to a bucket.
 * Saving artifacts to object storage is allows for the separation of build and deployment.
 */


/**
 * Stages:
 * Checkout - Checkout from git (must include submodules)
 * Build - Build based on supplied commands
 * Save - Save artifacts to object storage (s3 or gcs, using gsutil)
 * Deploy (Optional) - Optionally deploy site(s) to object storage
 */

// TODO: Support docker login for buildImage
// TODO: Document env vars available to commands, maybe include some extra git ones
// TODO: Add Save tagging/version policy

def call(body) {
    def config = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()

    def repo       = config.containsKey('repo') ? config.repo : null
    boolean dryRun = config.containsKey('dryRun') ? config.dryRun : false

    def buildImage      = config.containsKey('buildImage')      ? config.buildImage      : "docker.io/panubo/hugo:latest"
    def commands        = config.containsKey('commands')        ? config.commands        : ["hugo -d public", "hugo -D -d draft"]
    def artifactsBucket = config.containsKey('artifactsBucket') ? config.artifactsBucket : null
    def artifacts       = config.containsKey('artifacts')       ? config.artifacts       : [[dir: "public", deploy: null], [dir: "draft", deploy: null]]
    def credentialId    = config.containsKey('credentialId')    ? config.credentialId    : null

    node {
    ansiColor('xterm') {
        stage("Checkout") {
            if (env.BRANCH_NAME) {
                checkout scm
            }
            else if ((env.BRANCH_NAME == null) && (repo)) {
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: '*/master']],
                    doGenerateSubmoduleConfigurations: false,
                    extensions: [
                        [$class: 'SubmoduleOption', disableSubmodules: false, parentCredentials: false, recursiveSubmodules: false, reference: '', trackingSubmodules: false],
                        [$class: 'CloneOption', depth: 0, noTags: false, reference: '', shallow: true], 
                        [$class: 'CleanCheckout']
                    ],
                    submoduleCfg: [],
                    userRemoteConfigs: [
                        [credentialsId: null, url: repo]
                    ]
                    ])
            }
            else {
                error 'buildPlugin must be used as part of a Multibranch Pipeline *or* a `repo` argument must be provided'
            }
        }

        // If artifactsBucket is set upload archives artifacts
        // Each artifact can also have their own credentialId otherwise fallback to main credentialId

        // Configure credentials for Save stage
        def saveCredential = null
        if (artifactsBucket) {
            switch (artifactsBucket) {
                case ~/^s3:\/\/.*/:
                    saveCredential = [usernamePassword(credentialsId: credentialId, usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]
                    break
                case ~/^gs:\/\/.*/:
                    saveCredential = [file(credentialsId: credentialId, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]
                    break
                default:
                    error("Build failed because we don't support the repo protocol. Use s3:// or gs://")
                    break
            }
        }

        // It is possible to skip the build is the assets are already built
        if (buildImage) {
            stage("Build") {
                docker.image(buildImage).inside() {
                    commands.each {
                        sh(it)
                    }
                }
            }
        }

        docker.image("docker.io/panubo/gsutil").inside() {

            artifacts.each {
                def artifactName = it['dir'].replaceAll('/+$', '')
                def artifcatDeploy = it.containsKey('deploy') ? it['deploy'] : null
                def artifactCredentialId = it.containsKey('credentialId') ? it['credentialId'] : credentialId

                if (artifactsBucket) {
                    stage("Save Artifact ${artifactName}") {
                        echo("Uploading to ${artifactsBucket} w/ gsutil")
                        // Save artifact to artifactsBucket using saveCredential
                        def tag = "latest"
                        def cmd = "tar -c ${artifactName} | lz4 -c > ${artifactName}_${tag}.tar.lz4 && gsutil cp ${artifactName}_${tag}.tar.lz4 ${artifactsBucket}/${artifactName}_${tag}.tar.lz4"
                        if (dryRun) {
                            echo("DRYRUN")
                            echo(cmd)
                        } else {
                            withCredentials(saveCredential) {
                                sh("/entry.sh && " + cmd)
                            }
                        }
                    }
                }

                if (artifcatDeploy) {
                    def artifactCredential
                    switch (artifcatDeploy) {
                        case ~/^s3:\/\/.*/:
                            artifactCredential = [usernamePassword(credentialsId: artifactCredentialId, usernameVariable: 'AWS_ACCESS_KEY_ID', passwordVariable: 'AWS_SECRET_ACCESS_KEY')]
                            break
                        case ~/^gs:\/\/.*/:
                            artifactCredential = [file(credentialsId: artifactCredentialId, variable: 'GOOGLE_APPLICATION_CREDENTIALS')]
                            break
                        default:
                            error("Build failed because we don't support the repo protocol. Use s3:// or gs://")
                            break
                    }

                    stage("Deploy Artifact ${artifactName}") {
                        echo("Deploy artifact to ${artifcatDeploy}")
                        def cmd = "gsutil -m rsync -d -r ${artifactName}/ ${artifcatDeploy}/"
                        if (dryRun) {
                            echo("DRYRUN")
                            echo(cmd)
                        } else {
                            withCredentials(artifactCredential) {
                                sh("/entry.sh && " + cmd)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}
