# Generate and verify only trusted fixtures from this repository. Never load user saves.
# Run with CRuby or: java -jar build/verification-tools/jruby-complete-9.4.15.0.jar <this file> generate|verify
require 'fileutils'
require 'stringio'

class SaveTable
  attr_reader :tag, :payload
  def initialize(tag, payload = "\x00\x01\xff".b)
    @tag, @payload = tag, payload
  end
  def _dump(_depth)
    data = @payload.dup
    data.instance_variable_set(:@tag, @tag)
    data
  end
  def self._load(data)
    new(data.instance_variable_get(:@tag), data)
  end
end

root = File.expand_path('..', __dir__)
case ARGV.fetch(0)
when 'generate'
  fixtures = File.join(root, 'app/src/test/resources/saves')
  FileUtils.mkdir_p(fixtures)
  tag = 'shared table tag'
  table = SaveTable.new(tag)
  File.binwrite(File.join(fixtures, 'rgss-userdef-links.rxdata'), Marshal.dump([30, table, tag, table]))
  clock = Time.utc(2025, 4, 3, 12, 34, 56, 789123)
  File.binwrite(File.join(fixtures, 'rgss-time.rxdata'), Marshal.dump([30, clock, clock]))
  streams = StringIO.new(''.b)
  [['Hero', 0], 17, {'gold' => 3}].each { |value| Marshal.dump(value, streams) }
  File.binwrite(File.join(fixtures, 'rgss-streams.rxdata'), streams.string)
  puts "Generated Ruby Marshal fixtures with #{RUBY_DESCRIPTION}"
when 'verify'
  outputs = File.join(root, 'app/build/verification')
  data = Marshal.load(File.binread(File.join(outputs, 'rgss-userdef-links-patched.rxdata')))
  raise 'gold not edited' unless data[0] == 999
  raise 'table payload changed' unless data[1].payload == "\x00\x01\xff".b
  raise 'table attribute reference changed' unless data[1].tag.equal?(data[2])
  raise 'table identity changed' unless data[1].equal?(data[3])
  time_data = Marshal.load(File.binread(File.join(outputs, 'rgss-time-patched.rxdata')))
  raise 'time or identity changed' unless time_data[0] == 999 && time_data[1] == Time.utc(2025, 4, 3, 12, 34, 56, 789123) && time_data[1].equal?(time_data[2])
  streams = StringIO.new(File.binread(File.join(outputs, 'rgss-streams-patched.rxdata')))
  raise 'stream header changed' unless Marshal.load(streams) == ['Hero', 0]
  raise 'root integer not edited' unless Marshal.load(streams) == 99
  raise 'trailing stream changed' unless Marshal.load(streams) == {'gold' => 3} && streams.eof?
  puts "Ruby Marshal readback: custom dump attributes, references, Time and separate streams OK (#{RUBY_DESCRIPTION})"
else
  abort 'Expected generate or verify'
end
