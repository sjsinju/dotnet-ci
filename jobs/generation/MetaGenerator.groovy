// This project will generate new job generators based on changes to the repolist.txt, which will
// in turn cause those generators to run over the job generation in the local repos.

import jobs.generation.Utilities

// About this generator:
//
//  This generator is designed to run every time there is a change to
//    * The dotnet-ci repo's generation dir
//
//  A rerun of this could mean any number of things:
//    * Updated repolist.txt
//    * Updated utilities
//
//  Rerunning this generator will not automatically rerun all of the generators
//  below it (by design).  Users making changes to the overall generation structure
//  should recognize when there is a functional change being made and rerun all of the
//  generated jobs.

class Repo {
    String project
    String[] folders
    String branch
    String server
    String definitionScript

    def Repo(String project, String[] folders, String branch, String server, String definitionScript) {
        this.project = project
        this.folders = folders
        this.branch = branch
        this.server = server
        this.definitionScript = definitionScript
    }

    // Parse the input string and return a Repo object
    def static parseInputString(String input, def out) {
        // First element is the repo name.  Should be in <org>/<repo> format
        def projectInfo = input.tokenize()

        assert projectInfo.size() >= 1

        // First element is the repo name
        String project = projectInfo[0]
        String[] folders = null
        String branch = null
        // Server defaults to the primary server, dotnet-ci
        String server = 'dotnet-ci'
        // File name/path is usually netci.groovy, but can be set arbitrarily
        String definitionScript = 'netci.groovy'

        // Check whether it contains a single forward slash
        assert project.indexOf('/') != -1 && project.indexOf('/') == project.lastIndexOf('/')

        // Now walk the rest of the elements and set the rest of the properties
        def i = 1
        while (i < (projectInfo.size())) {
            def element = projectInfo[i]

            if (element.startsWith('folder=')) {
                // Parse out the folder names
                folders = element.substring('folder='.length()).tokenize('/')

                // If the folder name was root, just zero it out.  If they chose root, there should
                // only be one element
                assert folders.size() >= 1

                if (folders[0] == '<root>') {
                    assert folders.size() == 1
                    // Set an initial empty folder
                    folders = []
                }
            }
            else if(element.startsWith('branch=')) {
                branch = element.substring('branch='.length())
            }
            else if(element.startsWith('server=')) {
                server = element.substring('server='.length())
            }
            else if(element.startsWith('definitionScript=')) {
                definitionScript = element.substring('definitionScript='.length())
            }
            else {
                println("Unknown element " + element);
                assert false
            }
            i++
        }

        // If the folder was unset but branch was set to something, then we set the folder to the
        // repo name plus branch subfolder

        if (folders == null) {
            folders = [Utilities.getFolderName(project)]
            if (branch == null) {
                branch = 'master'
            }
            else {
                // Append the branch name to the folder list
                folders += Utilities.getFolderName(branch)
            }
        }
        else {
            // Folder already set, but set a default value for branch if it's not specified
            // Branch name is not appended to the folder list.
            if (branch == null) {
                branch = 'master'
            }
        }

        out.println("   folders = ${folders}")
        out.println("   branch = ${branch}")

        // Construct a new object and return

        return new Repo(project, folders, branch, server, definitionScript)
    }
}

Repo[] repos = []

streamFileFromWorkspace('dotnet-ci/jobs/data/repolist.txt').eachLine { line ->
    // Skip comment lines
    boolean skip = (line ==~ / *#.*/);
    line.trim()
    skip |= (line == '')
    if (skip) {
        // Return from closure
        return;
    }

    repos += Repo.parseInputString(line, out)
}

// Now that we have all the repos, generate the jobs
repos.each { repoInfo ->

    // Determine whether we should skip this repo becuase it resides on a different server
    if (repoInfo.server != ServerName) {
        return;
    }

    // Make the folders
    def generatorFolder = ''
    for (folderElement in repoInfo.folders) {
        if (generatorFolder == '') {
            generatorFolder = folderElement
        }
        else {
            // Append a new folder
            generatorFolder += "/${folderElement}"
        }
        folder(generatorFolder) {}
    }

    // Make the PR test folder
    def generatorPRTestFolder = "${generatorFolder}/GenPRTest"

    // Create a Folder for generator PR tests under that.
    folder(generatorPRTestFolder) {}

    [true, false].each { isPRTest ->
        def jobGenerator = job(Utilities.getFullJobName(repoInfo.project, 'generator', isPRTest, isPRTest ? generatorPRTestFolder : generatorFolder)) {
            // Need multiple scm's
            multiscm {
                git {
                    remote {
                        url('https://github.com/dotnet/dotnet-ci')
                    }
                    relativeTargetDir('dotnet-ci')
                    // dotnet-ci always pulls from master
                    branch('*/master')
                }
                //
                git {
                    remote {
                        github(repoInfo.project)

                        if (isPRTest) {
                            refspec('+refs/pull/*:refs/remotes/origin/pr/*')
                        }
                    }
                    def targetDir = Utilities.getProjectName(repoInfo.project)
                    // Want the relative to be just the project name
                    relativeTargetDir(targetDir)

                    // If PR, change to ${sha1}
                    // If not a PR, then the branch name should be the target branch
                    if (isPRTest) {
                        branch('${sha1}')
                    }
                    else {
                        branch("*/${repoInfo.branch}")
                    }

                    // Set up polling ignore
                    configure { node ->
                        node /'extensions' << 'hudson.plugins.git.extensions.impl.PathRestriction' {
                            // Not sure whether polling takes into account the target dir, so just
                            // put multiple entries
                            includedRegions "${targetDir}/${repoInfo.definitionScript}\n${repoInfo.definitionScript}"
                            excludedRegions ''
                        }
                    }
                }
            }

            // Add a parameter for the project, so that gets passed to the
            // DSL groovy file
            parameters {
                stringParam('GithubProject', repoInfo.project, 'Project name passed to the DSL generator')
                stringParam('GithubBranchName', repoInfo.branch, 'Branch name passed to the DSL generator')
            }

            // Add in the job generator logic

            steps {
                dsl {
                    // Loads DSL groovy file
                    external(Utilities.getProjectName(repoInfo.project) + "/${repoInfo.definitionScript}")

                    // Additional classpath should point to the utility repo
                    additionalClasspath('dotnet-ci')

                    // Generate jobs relative to the seed job.
                    lookupStrategy('SEED_JOB')

                    // PR tests should do nothing with the other jobs.
                    // Non-PR tests should disable the jobs, which will get cleaned
                    // up later.
                    if (isPRTest) {
                        removeAction('IGNORE')
                    }
                    else {
                        removeAction('DISABLE')
                    }
                    removeViewAction('DELETE')
                }

                // If this is a PR test job, we don't want the generated jobs
                // to actually trigger (say on a github PR, since that will be confusing
                // and wasteful.  We can accomplish this by adding another DSL step that does
                // nothing.  It will generate no jobs, but the remove action is DISABLE so the
                // jobs generated in the previous step will be disabled.

                if (isPRTest) {
                    dsl {
                         text('// Generate no jobs so the previously generated jobs are disabled')

                         // Generate jobs relative to the seed job.
                         lookupStrategy('SEED_JOB')
                         removeAction('DISABLE')
                         removeViewAction('DELETE')
                    }
                }
            }

            // Disable concurrent builds
            concurrentBuild(false)

            // 5 second quiet period before the job can be scheduled
            quietPeriod(5)

            wrappers {
                timestamps()
            }
        }

        // Set the job to run on any generator enabled node.  Basically just has to have git.
        Utilities.setMachineAffinity(jobGenerator, 'Generators', 'latest-or-auto')

        if (isPRTest) {
            // Enable the github PR trigger, but add a trigger phrase so
            // that it doesn't build on every change.
            Utilities.addGithubPRTriggerForBranch(jobGenerator, repoInfo.branch, jobGenerator.name, '(?i).*test\\W+ci.*')
        }
        else {
            // Enable the github push trigger.
            jobGenerator.with {
                triggers {
                    scm {
                        // For the second CI server, the names have changed around a bit.
                        // Special case that server for now
                        if (ServerName == 'dotnet-ci2') {
                            scmpoll_spec('H/15 * * * *')
                            ignorePostCommitHooks(true)
                        }
                        else {
                            scm('H/15 * * * *')
                        }
                    }
                }
            }
        }
    }
}
