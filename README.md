name: Build APK
on: workflow_dispatch
permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-24.04
    steps:
      - uses: actions/checkout@v6
      - uses: actions/setup-java@v5
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v6
        with:
          gradle-version: '8.9'
      - name: Build and verify release
        shell: bash
        run: |
          if [[ -f StatusSaver.zip ]]; then
            unzip -q StatusSaver.zip -d "$RUNNER_TEMP/source"
            settings=$(find "$RUNNER_TEMP/source" -type f \
              \( -name settings.gradle -o -name settings.gradle.kts \) -print -quit)
            test -n "$settings"
            cd "$(dirname "$settings")"
            mkdir -p .github/workflows
            cp "$GITHUB_WORKSPACE/.github/workflows/build.yml" .github/workflows/build.yml
          fi
          rm -f local.properties
          gradle --no-daemon assembleRelease
          apk=app/build/outputs/apk/release/app-release.apk
          "$ANDROID_HOME/build-tools/34.0.0/apksigner" verify "$apk"
          cp "$apk" "$RUNNER_TEMP/app-release.apk"
          zip -qr "$RUNNER_TEMP/StatusSaver-updated.zip" . \
            -x '.git/*' '.gradle/*' 'app/build/*' 'build/*'
      - uses: actions/upload-artifact@v4
        with:
          name: StatusSaver
          path: |
            ${{ runner.temp }}/app-release.apk
            ${{ runner.temp }}/StatusSaver-updated.zip
          if-no-files-found: error
