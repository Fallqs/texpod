#include <TurnIntent.h>
#include <gtest/gtest.h>

using textplayer::TurnIntent;

TEST(TextPlayerTurnIntent, StalePageCannotClaimNewerTurns) {
  TurnIntent turns;
  turns.press(1);
  const auto first = turns.snapshot();
  turns.press(1);
  turns.press(1);
  EXPECT_FALSE(turns.claim(first));
  turns.press(-1);
  const auto latest = turns.snapshot();
  EXPECT_EQ(TurnIntent::steps(latest), 2);
  ASSERT_TRUE(turns.claim(latest));
  turns.press(-1);
  EXPECT_EQ(TurnIntent::steps(turns.snapshot()), -1);
}

TEST(TextPlayerTurnIntent, SameOffsetAfterReversalStillInvalidatesOldRender) {
  TurnIntent turns;
  turns.press(1);
  const auto first = turns.snapshot();
  turns.press(-1);
  EXPECT_EQ(TurnIntent::steps(turns.snapshot()), 0);
  turns.press(1);
  EXPECT_EQ(TurnIntent::steps(turns.snapshot()), TurnIntent::steps(first));
  EXPECT_FALSE(turns.claim(first));
}

TEST(TextPlayerTurnIntent, SaturatesAndInvalidatesAcrossSessions) {
  TurnIntent turns;
  for (int i = 0; i <= TurnIntent::MAX_STEPS; ++i) turns.press(-1);
  const auto old = turns.snapshot();
  EXPECT_EQ(TurnIntent::steps(old), -TurnIntent::MAX_STEPS);
  turns.reset();
  EXPECT_EQ(TurnIntent::steps(turns.snapshot()), 0);
  EXPECT_FALSE(turns.claim(old));
}
