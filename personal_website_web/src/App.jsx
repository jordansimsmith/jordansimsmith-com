import {
  Container,
  Box,
  Text,
  Image,
  Group,
  Title,
  Stack,
  Anchor,
  Flex,
} from '@mantine/core';

export const App = () => {
  return (
    <Stack h="100%">
      <Box component="header" bg="blue.1">
        <Container p="xs">
          <Title order={2}>Jordan Sim-Smith</Title>
        </Container>
      </Box>

      <Box component="main" flex="1" mt="lg">
        <Container>
          <Flex direction={{ base: 'column', md: 'row' }} gap="lg">
            <Box maw="400px">
              <Image
                src="/profile.jpg"
                alt="Jordan ontop a snowy mountain"
                radius="lg"
              />
            </Box>
            <Stack>
              <Title order={1}>Hello there 👋</Title>

              <Text>
                I'm a Backend Engineer{' '}
                <Anchor href="https://canva.com/" target="_blank">
                  @Canva
                </Anchor>{' '}
                and a graduate from the{' '}
                <Anchor href="https://www.auckland.ac.nz/" target="_blank">
                  University of Auckland
                </Anchor>
                .
              </Text>

              <Text>
                My passion for software engineering is fed from my drive for
                learning and self improvement. The opportunity to explore
                potential enhancements motivates me to seek out areas of
                improvement, instead of letting the technology I am using become
                stale.
              </Text>

              <Text>
                One way that I like to challenge myself and learn new skills is
                to build personal projects. This website is an example of one!
                Check out my{' '}
                <Anchor
                  href="https://github.com/jordansimsmith"
                  target="_blank"
                >
                  GitHub
                </Anchor>{' '}
                to see what other cool projects that I have been working on.
              </Text>

              <Text>
                Thank you for taking the time to learn a bit about me. Please do
                not hesitate to contact me on{' '}
                <Anchor
                  href="https://linkedin.com/in/jordansimsmith"
                  target="_blank"
                >
                  LinkedIn
                </Anchor>{' '}
                if you would like to get in touch. Have a great day!
              </Text>
            </Stack>
          </Flex>
        </Container>
      </Box>

      <Box component="footer" bg="gray.1">
        <Container p="xs">
          <Stack align="center">
            <Text c="gray.7">Jordan Sim-Smith</Text>
            <Group>
              <Anchor
                href="https://github.com/jordansimsmith"
                target="_blank"
                c="gray.7"
                underline="always"
              >
                GitHub
              </Anchor>
              <Anchor
                href="https://linkedin.com/in/jordansimsmith"
                target="_blank"
                c="gray.7"
                underline="always"
              >
                LinkedIn
              </Anchor>
            </Group>
          </Stack>
        </Container>
      </Box>
    </Stack>
  );
};
