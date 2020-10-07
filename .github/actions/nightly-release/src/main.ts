import * as core from '@actions/core';
import { GitHub, context } from '@actions/github';
import Constants from './Constants';

async function run(): Promise<void> {
    // taken from https://github.com/actions/create-release/blob/main/src/create-release.js

    // Get authenticated GitHub client (Ocktokit): https://github.com/actions/toolkit/tree/master/packages/github#usage
    const github = new GitHub(process.env.GITHUB_TOKEN);
    
    // Get owner and repo from context of payload that triggered the action
    const { owner: owner, repo: repo } = context.repo;

    // Get the inputs from the workflow file: https://github.com/actions/toolkit/tree/master/packages/core#inputsoutputs
    const tagName = core.getInput('tag_name', { required: true });

    // This removes the 'refs/tags' portion of the string, i.e. from 'refs/tags/v1.10.15' to 'v1.10.15'
    const tag = tagName.replace('refs/tags/', '');
    const releaseName = core.getInput('release_name', { required: true }).replace('refs/tags/', '');
    const body = core.getInput('body', { required: false });
    const draft = false;
    const prerelease = true;
    const commitish = context.sha;

    // Create a release
    // API Documentation: https://developer.github.com/v3/repos/releases/#create-a-release
    // Octokit Documentation: https://octokit.github.io/rest.js/#octokit-routes-repos-create-release
    const createReleaseResponse = await github.repos.createRelease({
        owner,
        repo,
        tag_name: tag,
        name: releaseName,
        body: Constants.INVISIBLE_BODY_PREAMBLE + body,
        draft,
        prerelease,
        target_commitish: commitish
      });

    // Get the ID, html_url, and upload URL for the created Release from the response
    const {
        data: { id: releaseId, html_url: htmlUrl, upload_url: uploadUrl }
    } = createReleaseResponse;
  
    // Set the output variables for use by other actions: https://github.com/actions/toolkit/tree/master/packages/core#inputsoutputs
    core.setOutput('id', releaseId);
    core.setOutput('html_url', htmlUrl);
    core.setOutput('upload_url', uploadUrl);
}

run()
    .catch((err) => core.setFailed(err));
