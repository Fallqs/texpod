add_executable(TextPlayerWireTest
  WireTest.cpp
  TurnIntentTest.cpp
  SdGlyphStreamTest.cpp
  SdFontStoreTest.cpp
  ${REPO_ROOT}/lib/TextPlayer/SdFontStore.cpp
)

target_include_directories(TextPlayerWireTest PRIVATE
  stubs
  ${REPO_ROOT}/lib/TextPlayer
)

target_compile_options(TextPlayerWireTest PRIVATE -Wall -Wextra -pedantic)
target_link_libraries(TextPlayerWireTest PRIVATE GTest::gtest_main)
gtest_discover_tests(TextPlayerWireTest)
