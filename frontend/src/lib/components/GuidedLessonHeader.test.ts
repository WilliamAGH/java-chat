import { describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/svelte";
import GuidedLessonHeader from "./GuidedLessonHeader.svelte";

const SELECTED_LESSON_TITLE = "Records";

describe("GuidedLessonHeader heading accessibility", () => {
  it("exposes the selected lesson title as the sole level-one heading", () => {
    const { getAllByRole, getByRole } = render(GuidedLessonHeader, {
      props: {
        lessonTitle: SELECTED_LESSON_TITLE,
        onReturnToLessons: vi.fn(),
      },
    });

    const selectedLessonHeading = getByRole("heading", {
      name: SELECTED_LESSON_TITLE,
      level: 1,
    });

    expect(getAllByRole("heading", { level: 1 })).toHaveLength(1);
    expect(selectedLessonHeading.tagName).toBe("H1");
  });
});
