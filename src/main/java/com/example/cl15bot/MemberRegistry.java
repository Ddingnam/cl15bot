package com.example.cl15bot;

import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class MemberRegistry {
    private final NotionStudyClient notion;

    public MemberRegistry(NotionStudyClient notion) {
        this.notion = notion;
    }

    public Optional<String> pageIdFor(String memberName) {
        return notion.findMemberPageId(memberName);
    }

    public static class DuplicateMemberException extends RuntimeException {
        public DuplicateMemberException() {
            super("Members에 같은 이름이 중복 등록되어 있습니다. 이름만으로 구분할 수 없어 저장하지 않았습니다. 관리자에게 확인을 요청해주세요.");
        }
    }
}
