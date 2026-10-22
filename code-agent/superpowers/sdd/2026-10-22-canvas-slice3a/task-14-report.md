# Task 14 report
Status: DONE. Branch s3a/a. Tests: :services:voice:testDebugUnitTest 170 tests, 0 failures (24 new in commands.*; RED run was 17/24 failing against stubs).
Files (services/voice, package com.rokid.context.voice.commands): MarkPhrases.kt, MarkCommandParser.kt (MarkCommand sealed + parser), EnglishClauses.kt, LanguageRegistry.kt (LanguagePack), VoiceCommandGroup.kt (+VoiceCommandGate); tests MarkCommandParserTest, EnglishClausesTest, LanguagePackTest, VoiceCommandGroupTest.
Ported from Live Fit (EnglishClauses, LanguageRegistry, VoiceCommandGroup): structure only; the workout/music rules were not ported. Live Fit files carry no licence header; KDoc provenance notes (Apache-2.0, same owner) added. No NOTICE file exists in this repo, none added.
