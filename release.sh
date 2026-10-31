#!/bin/bash
set -euo pipefail

YES=0
NEW_NAME=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        -y|--yes) YES=1 ;;
        *)
            [[ -z "$NEW_NAME" ]] || { echo "只能指定一个版本号"; exit 1; }
            NEW_NAME="$1"
            ;;
    esac
    shift
done

cd "$(dirname "$0")"
if [[ -z "$NEW_NAME" ]]; then
    [[ "$YES" -eq 0 ]] || { echo "用法：bash release.sh -y 2.6.5"; exit 1; }
    read -r -p "请输入发布版本（如 2.6.5 或 2.6.5-beta1）: " NEW_NAME
fi
NEW_NAME="${NEW_NAME#v}"
if [[ ! "$NEW_NAME" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-beta([1-9][0-9]*)?)?$ ]]; then
    echo "版本格式错误：$NEW_NAME"
    exit 1
fi
# Keep version bounds consistent with Gradle's versionCode encoding.
MAJOR="${BASH_REMATCH[1]}"
MINOR="${BASH_REMATCH[2]}"
PATCH="${BASH_REMATCH[3]}"
BETA="${BASH_REMATCH[5]}"
if [[ ${#MAJOR} -gt 4 || ${#MINOR} -gt 2 || ${#PATCH} -gt 2 || ${#BETA} -gt 2 ]]; then
    echo "版本范围错误：major 0..2099，minor/patch 0..99，beta 1..98"
    exit 1
fi
if (( MAJOR > 2099 || MINOR > 99 || PATCH > 99 )) || [[ -n "$BETA" && "$BETA" -gt 98 ]] || [[ "$NEW_NAME" == "0.0.0-beta" ]]; then
    echo "版本范围错误：major 0..2099，minor/patch 0..99，beta 1..98"
    exit 1
fi

[[ -z "$(git status --porcelain --untracked-files=no)" ]] || { echo "请先提交已跟踪文件的修改，再发布"; exit 1; }
CURRENT_BRANCH="$(git symbolic-ref --quiet --short HEAD)" || { echo "请在分支上发布"; exit 1; }
REMOTE="$(git config --get "branch.$CURRENT_BRANCH.remote")" || { echo "当前分支没有配置远程仓库"; exit 1; }
[[ "$REMOTE" != "." ]] || { echo "当前分支未关联外部远程仓库"; exit 1; }
REMOTE_REF="$(git config --get "branch.$CURRENT_BRANCH.merge")"
TAG_NAME="v$NEW_NAME"
RELEASE_COMMIT="$(git rev-parse HEAD)"
TAG_EXISTS=0
if git show-ref --verify --quiet "refs/tags/$TAG_NAME"; then
    TAG_COMMIT="$(git rev-parse --verify "refs/tags/$TAG_NAME^{commit}")" || { echo "Tag $TAG_NAME 未指向提交，请使用新版本号"; exit 1; }
    [[ "$TAG_COMMIT" == "$RELEASE_COMMIT" ]] || { echo "Tag $TAG_NAME 指向其他提交，请使用新版本号"; exit 1; }
    TAG_EXISTS=1
fi
REMOTE_TAG="$(git ls-remote --tags "$REMOTE" "refs/tags/$TAG_NAME")"
[[ -z "$REMOTE_TAG" ]] || { echo "远程 Tag $TAG_NAME 已存在，请使用新版本号"; exit 1; }

echo "即将发布 ${TAG_NAME} 到 ${REMOTE}，分支 ${CURRENT_BRANCH} -> ${REMOTE_REF}"
if [[ "$YES" -eq 0 ]]; then
    read -r -p "确认创建 Tag 并推送？(y/n) " REPLY
    [[ "$REPLY" == "y" || "$REPLY" == "Y" ]] || exit 0
fi
if [[ "$TAG_EXISTS" -eq 0 ]]; then
    git tag -a "$TAG_NAME" "$RELEASE_COMMIT" -m "Release $TAG_NAME"
fi
if git push --atomic "$REMOTE" "$RELEASE_COMMIT:$REMOTE_REF" "refs/tags/$TAG_NAME"; then
    echo "已推送 ${TAG_NAME}，GitHub Actions 将自动填充版本并构建 APK"
else
    PUSH_STATUS=$?
    echo "推送失败，已保留本地 Tag ${TAG_NAME}；修复推送问题后，可在当前提交重试发布" >&2
    exit "$PUSH_STATUS"
fi
