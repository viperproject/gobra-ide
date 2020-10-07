import * as core from '@actions/core';
import { GitHub, context } from '@actions/github';
import Constants from './Constants';

async function run(): Promise<void> {
    // Get authenticated GitHub client (Ocktokit): https://github.com/actions/toolkit/tree/master/packages/github#usage
    const github = new GitHub(process.env.GITHUB_TOKEN);

    // Get owner and repo from context of payload that triggered the action
    const { owner: owner, repo: repo } = context.repo;

    const keepNum: number = Number(core.getInput('keep_num', { required: false })) || 0;

    const keepTags: boolean = core.getInput('keep_tags', { required: false }) === 'true';

    // see https://octokit.github.io/rest.js/v18#repos-list-releases
    const { data: releases } = await github.repos.listReleases({
        owner,
        repo
    });

    const releasesToBeDeleted = releases
        .filter(release => release.prerelease)
        // we assume that the releases are sorted by release date
        // remove the first `keep` many releases:
        .filter((_, index) => index > keepNum)
        // remove releases not created by this action:
        .filter(release => release.body.startsWith(Constants.INVISIBLE_BODY_PREAMBLE))
        // reverse releases to start deleting the oldest one:
        .reverse();

    async function deleteRelease(release: Release): Promise<void> {
        // all assets have to be deleted first:
        for (const asset of release.assets) {
            // see https://octokit.github.io/rest.js/v18#repos-delete-release-asset
            await github.repos.deleteReleaseAsset({
                owner,
                repo,
                asset_id: asset.id
            });
        }
        // then delete the actual release:
        // see https://octokit.github.io/rest.js/v18#repos-delete-release
        await github.repos.deleteRelease({
            owner,
            repo,
            release_id: release.id
        });
        if (!keepTags) {
            // delete the associated tag:
            // see https://octokit.github.io/rest.js/v18#git-delete-ref
            await github.git.deleteRef({
                owner,
                repo,
                ref: 'tags/' + release.tag_name
            })
        }
    }

    for (const release of releasesToBeDeleted) {
        await deleteRelease(release);
        console.log(`Release '${release.name}' was successfully deleted`);
    }
    console.info(`${releasesToBeDeleted.length} release(s) have been deleted`);
}

interface Release {
    id: number,
    name: string,
    body: string,
    tag_name: string,
    assets: Asset[]
}

interface Asset {
    id: number
}

run()
    .catch((err) => core.setFailed(err));
