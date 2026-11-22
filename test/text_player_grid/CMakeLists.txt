add_executable(TextPlayerGridTest
  GridPageTest.cpp
)

target_include_directories(TextPlayerGridTest PRIVATE
  ${REPO_ROOT}/lib/TextPlayer
)

target_compile_options(TextPlayerGridTest PRIVATE -Wall -Wextra -pedantic)
target_link_libraries(TextPlayerGridTest PRIVATE GTest::gtest_main)
gtest_discover_tests(TextPlayerGridTest)
