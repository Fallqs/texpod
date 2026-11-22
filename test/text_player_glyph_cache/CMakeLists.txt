add_executable(TextPlayerGlyphCacheTest
  GlyphCacheTest.cpp
)

target_include_directories(TextPlayerGlyphCacheTest PRIVATE
  ${REPO_ROOT}/lib/TextPlayer
)

target_compile_options(TextPlayerGlyphCacheTest PRIVATE -Wall -Wextra -pedantic)
target_link_libraries(TextPlayerGlyphCacheTest PRIVATE GTest::gtest_main)
gtest_discover_tests(TextPlayerGlyphCacheTest)
