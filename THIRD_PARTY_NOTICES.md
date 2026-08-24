# 서드파티 라이선스 고지

이 저장소에는 아래 공개 프로젝트에서 가져오거나 생성한 파일이 포함되어 있다.
경로는 저장소 루트 기준이며, 원본을 수정한 경우에도 해당 원본 라이선스와
저작권 고지를 유지한다. 각 Skill 폴더의 기존 `LICENSE` 파일은 해당 폴더를
독립적으로 복사할 때도 고지가 따라가도록 유지한다.

## 구성요소와 적용 경로

| 구성요소 | 저장소 내 적용 경로 | 원본 식별 정보 | License | 비고 |
|---|---|---|---|---|
| wshobson/agents | `.agents/skills/api-design-principles/**`, `.agents/skills/error-handling-patterns/**`, `.agents/skills/fastapi-templates/**`, `.agents/skills/python-testing-patterns/**`, `.claude/skills/api-design-principles/**` | [commit `2b49247f1347d9cbd90edf869e5412563c3945cf`](https://github.com/wshobson/agents/tree/2b49247f1347d9cbd90edf869e5412563c3945cf) | MIT | 보안, 실행 가능성 및 도구 호환성을 위해 일부 예제와 설명 수정 |
| DietrichGebert/ponytail | `.agents/skills/ponytail*/**`, `.claude/skills/ponytail*/**` | [commit `2ed6c52c9d7e5e56942508591085fd45dea277d3`](https://github.com/DietrichGebert/ponytail/tree/2ed6c52c9d7e5e56942508591085fd45dea277d3/skills) | MIT | 본체와 `audit`, `debt`, `gain`, `help`, `review` 보조 Skill 포함 |
| GitHub Spec Kit | `.specify/**`, `.agents/skills/speckit-*/**`, `.claude/skills/speckit-*/**` | [`v1.0.1`, commit `9118ed15a0ba65053469a94c560ea5d233f75884`](https://github.com/github/spec-kit/tree/9118ed15a0ba65053469a94c560ea5d233f75884) | MIT | 설치 manifest의 32개 파일 기준; 프로젝트 산출물과 설정은 수정 가능 |
| UI/UX Pro Max | `.claude/skills/ui-ux-pro-max/**` | [`2.13.0`, commit `a38d04c3d5c298c851dbe5e6ee1965ee3de42cb5`](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill/tree/a38d04c3d5c298c851dbe5e6ee1965ee3de42cb5) | MIT | CLI 설치본이며 `SKILL.md`는 패키징 변형 |
| Compose Expert | `.claude/skills/compose-expert/**` | [`aldefy/compose-skill`](https://github.com/aldefy/compose-skill), version `2.4.0` | MIT | MIT 전문은 `.claude/skills/compose-expert/LICENSE`에 유지 |
| Android Open Source Project 및 JetBrains Compose Multiplatform | `.claude/skills/compose-expert/references/source-code/**` | 각 파일 머리말과 Compose Expert 고지 참조 | Apache-2.0 | 전문은 `.claude/skills/compose-expert/LICENSE-APACHE-2.0.txt`에 유지 |
| Phosphor Icons | `.claude/skills/ui-ux-pro-max/data/phosphor-icons-upstream.json` 및 이로부터 생성된 아이콘 카탈로그 | [phosphor-icons/core](https://github.com/phosphor-icons/core) | MIT | 아이콘 메타데이터 snapshot |

`ui-ux-pro-max/data/google-font-licenses.json`에는 카탈로그에 등재된 각 Google
Fonts 글꼴의 라이선스 식별 정보와 원문이 포함되어 있다. 저장소에는 해당
글꼴 바이너리를 포함하지 않으며, 이 메타데이터 파일은 그대로 유지한다.

## wshobson/agents

MIT License

Copyright (c) 2024 Seth Hobson

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## DietrichGebert/ponytail

MIT License

Copyright (c) 2026 DietrichGebert

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## GitHub Spec Kit

MIT License

Copyright GitHub, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## UI/UX Pro Max

MIT License

Copyright (c) 2024 Next Level Builder

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Phosphor Icons

MIT License

Copyright (c) 2023 Phosphor Icons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
