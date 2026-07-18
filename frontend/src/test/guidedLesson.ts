import { GuidedLessonSchema, type GuidedLesson } from "../lib/validation/schemas";
import { validateWithSchema } from "../lib/validation/validate";

const TEST_GUIDED_LESSON_TECHNOLOGY = "Java";
const TEST_GUIDED_LESSON_DOC_SET = "dev-java";

/** Builds guided lesson metadata through the canonical frontend schema. */
export function createGuidedLessonFixture(
  lessonSlug: string,
  lessonTitle: string,
  lessonSummary: string,
): GuidedLesson {
  const guidedLessonValidation = validateWithSchema(
    GuidedLessonSchema,
    {
      slug: lessonSlug,
      title: lessonTitle,
      summary: lessonSummary,
      keywords: [],
      technology: TEST_GUIDED_LESSON_TECHNOLOGY,
      docSet: [TEST_GUIDED_LESSON_DOC_SET],
    },
    `guided lesson test projection [slug=${lessonSlug}]`,
  );

  if (!guidedLessonValidation.success) {
    throw new Error(`Expected the guided lesson fixture to validate [slug=${lessonSlug}]`);
  }

  return guidedLessonValidation.validated;
}
